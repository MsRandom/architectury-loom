/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2022 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.configuration.providers.forge;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.oceanlabs.mcp.mcinjector.adaptors.ParameterAnnotationFixer;
import dev.architectury.tinyremapper.MetaInfFixer;
import dev.architectury.tinyremapper.OutputConsumerPath;
import dev.architectury.tinyremapper.TinyRemapper;
import net.minecraftforge.binarypatcher.ConsoleTool;
import org.apache.commons.io.output.NullOutputStream;
import org.gradle.api.Project;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.accesstransformer.AccessTransformerJarProcessor;
import net.fabricmc.loom.configuration.providers.minecraft.MergedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.MappingsProviderVerbose;
import net.fabricmc.loom.util.ThreadingUtils;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.function.FsPathConsumer;
import net.fabricmc.loom.util.srg.InnerClassRemapper;
import net.fabricmc.loom.util.srg.SpecialSourceExecutor;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public class MinecraftPatchedProvider extends MergedMinecraftProvider {
	private static final String LOOM_PATCH_VERSION_KEY = "Loom-Patch-Version";
	private static final String CURRENT_LOOM_PATCH_VERSION = "6";
	private static final String NAME_MAPPING_SERVICE_PATH = "/inject/META-INF/services/cpw.mods.modlauncher.api.INameMappingService";

	// Step 1: Merge (global)
	private File minecraftMergedJar;
	// Step 2: Remap Minecraft to SRG
	private File minecraftMergedSrgJar;
	// Step 3: Binary Patch
	private File minecraftMergedPatchedSrgJar;
	// Step 4: Access Transform
	private File minecraftMergedPatchedSrgAtJar;
	// Step 5: Remap Patched AT & Forge to Official
	private File minecraftMergedPatchedAtJar;
	private File minecraftClientExtra;

	private boolean serverJarInitialized = false;
	private boolean notch;

	public static MergedMinecraftProvider createMergedMinecraftProvider(Project project) {
		return LoomGradleExtension.get(project).isForge() ? new MinecraftPatchedProvider(project) : new MergedMinecraftProvider(project);
	}

	public static MinecraftPatchedProvider get(Project project) {
		MinecraftProvider provider = LoomGradleExtension.get(project).getMinecraftProvider();

		if (provider instanceof MinecraftPatchedProvider patched) {
			return patched;
		} else {
			throw new UnsupportedOperationException("Project " + project.getPath() + " does not use MinecraftPatchedProvider!");
		}
	}

	public MinecraftPatchedProvider(Project project) {
		super(project);
	}

	private void initPatchedFiles() {
		String forgeVersion = getExtension().getForgeProvider().getVersion().getCombined();
		File forgeWorkingDir = dir("forge/" + forgeVersion);
		String patchId = "forge-" + forgeVersion + "-";

		setJarPrefix(patchId);

		minecraftMergedJar = new File(forgeWorkingDir, "minecraft-merged.jar");
		minecraftMergedSrgJar = new File(forgeWorkingDir, "merged-srg.jar");
		minecraftMergedPatchedSrgJar = new File(forgeWorkingDir, "merged-srg-patched.jar");
		minecraftMergedPatchedSrgAtJar = new File(forgeWorkingDir, "merged-srg-at-patched.jar");
		minecraftMergedPatchedAtJar = new File(forgeWorkingDir, "merged-at-patched.jar");
		minecraftClientExtra = new File(forgeWorkingDir, "forge-client-extra.jar");

		JsonObject userdevConfig = getExtension().getForgeUserdevProvider().getConfig();
		JsonElement notchObf = userdevConfig.get("notchObf");
		notch = notchObf != null && notchObf.getAsBoolean();

		if (notch) {
			minecraftMergedSrgJar = minecraftMergedPatchedSrgJar;
			minecraftMergedPatchedSrgJar = new File(minecraftMergedSrgJar.getParentFile(), "merged-patched.jar");
		}
	}

	private File getEffectiveServerJar() throws IOException {
		if (getServerBundleMetadata() != null) {
			if (!serverJarInitialized) {
				extractBundledServerJar();
				serverJarInitialized = true;
			}

			return getMinecraftExtractedServerJar();
		} else {
			return getMinecraftServerJar();
		}
	}

	public void cleanAllCache() {
		for (File file : getGlobalCaches()) {
			file.delete();
		}
	}

	private File[] getGlobalCaches() {
		return new File[] {
				minecraftMergedSrgJar,
				minecraftMergedPatchedSrgJar,
				minecraftMergedJar,
				minecraftClientExtra,
				minecraftMergedPatchedSrgAtJar,
				minecraftMergedPatchedAtJar
		};
	}

	private void checkCache() throws IOException {
		if (isRefreshDeps() || Stream.of(getGlobalCaches()).anyMatch(((Predicate<File>) File::exists).negate())
				|| !isPatchedJarUpToDate(minecraftMergedPatchedAtJar)) {
			cleanAllCache();
		}
	}

	@Override
	public void provide() throws Exception {
		super.provide();
		initPatchedFiles();
		checkCache();

		if (!minecraftMergedJar.exists()) {
			mergeJars(getProject().getLogger());
		}

		if (notch) {
			// Reverse the order if it requires the patches to be applied to the non-srg game.
			if (!minecraftMergedPatchedSrgJar.exists()) {
				patchJar(minecraftMergedJar, getProject().getLogger());
			}

			if (!minecraftMergedSrgJar.exists()) {
				produceSrgJar(mergeForge(minecraftMergedPatchedSrgJar.toPath()));
			}

			if (!minecraftMergedPatchedSrgAtJar.exists()) {
				accessTransformForge(minecraftMergedSrgJar, getProject().getLogger());
			}
		} else {
			if (!minecraftMergedSrgJar.exists()) {
				produceSrgJar(minecraftMergedJar.toPath());
			}

			if (!minecraftMergedPatchedSrgJar.exists()) {
				patchJar(minecraftMergedSrgJar, getProject().getLogger());
			}

			if (!minecraftMergedPatchedSrgAtJar.exists()) {
				accessTransformForge(mergeForge(minecraftMergedPatchedSrgJar.toPath()).toFile(), getProject().getLogger());
			}
		}
	}

	public void remapJar() throws Exception {
		remapPatchedJar(getProject().getLogger());
		fillClientExtraJar();

		DependencyProvider.addDependency(getProject(), minecraftClientExtra, Constants.Configurations.FORGE_EXTRA);
	}

	@Override
	protected void mergeJars() throws IOException {
		// Don't merge jars in the superclass
	}

	private void fillClientExtraJar() throws IOException {
		Files.deleteIfExists(minecraftClientExtra.toPath());
		FileSystemUtil.getJarFileSystem(minecraftClientExtra, true).close();

		copyNonClassFiles(getMinecraftClientJar(), minecraftClientExtra);
	}

	private TinyRemapper buildRemapper(Path input) throws IOException {
		Path[] libraries = TinyRemapperHelper.getMinecraftDependencies(getProject());
		MemoryMappingTree mappingsWithSrg = getExtension().getMappingsProvider().getMappingsWithSrg();

		TinyRemapper remapper = TinyRemapper.newRemapper()
				.logger(getProject().getLogger()::lifecycle)
				.logUnknownInvokeDynamic(false)
				.withMappings(TinyRemapperHelper.create(mappingsWithSrg, "srg", "official", true))
				.withMappings(InnerClassRemapper.of(InnerClassRemapper.readClassNames(input), mappingsWithSrg, "srg", "official"))
				.renameInvalidLocals(true)
				.rebuildSourceFilenames(true)
				.build();

		if (getProject().getGradle().getStartParameter().getLogLevel().compareTo(LogLevel.LIFECYCLE) < 0) {
			MappingsProviderVerbose.saveFile(remapper);
		}

		remapper.readClassPath(libraries);
		remapper.prepareClasses();
		return remapper;
	}

	// Remap official merged jar to MCPConfig remapped srg jar
	private void produceSrgJar(Path input) throws IOException {
		Path tmpSrg = getToSrgMappings();
		Set<File> mcLibs = getProject().getConfigurations().getByName(Constants.Configurations.MINECRAFT_DEPENDENCIES).resolve();
		Files.copy(SpecialSourceExecutor.produceSrgJar(getExtension().getMcpConfigProvider().getRemapAction(), getProject(), "joined", mcLibs, input, tmpSrg), minecraftMergedSrgJar.toPath());
	}

	private Path mergeForge(Path input) throws IOException {
		Path tmpForgeMerged = input.getParent().resolve("minecraft-forge-merged.jar");
		File forgeJar = getForgeJar();
		Files.deleteIfExists(tmpForgeMerged);

		try (
				FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(tmpForgeMerged, true);
				FileSystemUtil.Delegate minecraftFs = FileSystemUtil.getJarFileSystem(input, false);
				FileSystemUtil.Delegate forgeFs = FileSystemUtil.getJarFileSystem(forgeJar, false)
		) {
			Files.walkFileTree(minecraftFs.get().getPath("."), new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Path out = fs.get().getPath(file.toString());
					Files.createDirectories(out.getParent());
					Files.copy(file, out);
					return FileVisitResult.CONTINUE;
				}
			});

			Path forgeRoot = forgeFs.get().getPath(".");
			Files.walkFileTree(forgeRoot, new SimpleFileVisitor<>() {
				private final List<Pattern> patterns;

				{
					JsonArray universalFilters = getExtension().getForgeUserdevProvider().getConfig().getAsJsonArray("universalFilters");

					if (universalFilters == null) {
						patterns = Collections.emptyList();
					} else {
						patterns = StreamSupport.stream(universalFilters.spliterator(), false).map(filter -> Pattern.compile(filter.getAsString())).collect(Collectors.toList());
					}
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					String path = forgeRoot.relativize(file).toString();

					if (!patterns.isEmpty() && patterns.stream().noneMatch(pattern -> pattern.matcher(path).matches())) {
						return FileVisitResult.CONTINUE;
					}

					Path out = fs.get().getPath(path);

					if (Files.notExists(out)) {
						if (MetaInfFixer.INSTANCE.canTransform(null, out)) {
							try (InputStream input = Files.newInputStream(file)) {
								MetaInfFixer.INSTANCE.transform(fs.get().getPath("."), out, input, null);
							}
						} else {
							Path parent = out.getParent();

							if (parent != null) {
								Files.createDirectories(parent);
							}

							Files.copy(file, out);
						}
					}

					return FileVisitResult.CONTINUE;
				}
			});
		}

		copyUserdevFiles(getForgeUserdevJar().toPath().toFile(), tmpForgeMerged.toFile());

		return tmpForgeMerged;
	}

	private Path getToSrgMappings() throws IOException {
		if (getExtension().getSrgProvider().isTsrgV2()) {
			return getExtension().getSrgProvider().getMergedMojangRaw();
		} else {
			return getExtension().getMcpConfigProvider().getMappings();
		}
	}

	private void fixParameterAnnotation(File jarFile) throws Exception {
		getProject().getLogger().info(":fixing parameter annotations for " + jarFile.getAbsolutePath());
		Stopwatch stopwatch = Stopwatch.createStarted();

		try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + jarFile.toURI()), ImmutableMap.of("create", false))) {
			ThreadingUtils.TaskCompleter completer = ThreadingUtils.taskCompleter();

			for (Path file : (Iterable<? extends Path>) Files.walk(fs.getPath("/"))::iterator) {
				if (!file.toString().endsWith(".class")) continue;

				completer.add(() -> {
					byte[] bytes = Files.readAllBytes(file);
					ClassReader reader = new ClassReader(bytes);
					ClassNode node = new ClassNode();
					ClassVisitor visitor = new ParameterAnnotationFixer(node, null);
					reader.accept(visitor, 0);

					ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
					node.accept(writer);
					byte[] out = writer.toByteArray();

					if (!Arrays.equals(bytes, out)) {
						Files.delete(file);
						Files.write(file, out);
					}
				});
			}

			completer.complete();
		}

		getProject().getLogger().info(":fixing parameter annotations for " + jarFile.getAbsolutePath() + " in " + stopwatch);
	}

	private void deleteParameterNames(File jarFile) throws Exception {
		getProject().getLogger().info(":deleting parameter names for " + jarFile.getAbsolutePath());
		Stopwatch stopwatch = Stopwatch.createStarted();

		try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + jarFile.toURI()), ImmutableMap.of("create", false))) {
			ThreadingUtils.TaskCompleter completer = ThreadingUtils.taskCompleter();
			Pattern vignetteParameters = Pattern.compile("p_[0-9a-zA-Z]+_(?:[0-9a-zA-Z]+_)?");

			for (Path file : (Iterable<? extends Path>) Files.walk(fs.getPath("/"))::iterator) {
				if (!file.toString().endsWith(".class")) continue;

				completer.add(() -> {
					byte[] bytes = Files.readAllBytes(file);
					ClassReader reader = new ClassReader(bytes);
					ClassWriter writer = new ClassWriter(0);

					reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
						@Override
						public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
							return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
								@Override
								public void visitParameter(String name, int access) {
									if (vignetteParameters.matcher(name).matches()) {
										super.visitParameter(null, access);
									} else {
										super.visitParameter(name, access);
									}
								}

								@Override
								public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
									if (!vignetteParameters.matcher(name).matches()) {
										super.visitLocalVariable(name, descriptor, signature, start, end, index);
									}
								}
							};
						}
					}, 0);

					byte[] out = writer.toByteArray();

					if (!Arrays.equals(bytes, out)) {
						Files.delete(file);
						Files.write(file, out);
					}
				});
			}

			completer.complete();
		}

		getProject().getLogger().info(":deleting parameter names for " + jarFile.getAbsolutePath() + " in " + stopwatch);
	}

	private File getForgeJar() {
		return getExtension().getForgeUniversalProvider().getForge();
	}

	private File getForgeUserdevJar() {
		return getExtension().getForgeUserdevProvider().getUserdevJar();
	}

	private boolean isPatchedJarUpToDate(File jar) throws IOException {
		if (!jar.exists()) return false;

		byte[] manifestBytes = ZipUtils.unpackNullable(jar.toPath(), "META-INF/MANIFEST.MF");

		if (manifestBytes == null) {
			return false;
		}

		Manifest manifest = new Manifest(new ByteArrayInputStream(manifestBytes));
		Attributes attributes = manifest.getMainAttributes();
		String value = attributes.getValue(LOOM_PATCH_VERSION_KEY);

		if (Objects.equals(value, CURRENT_LOOM_PATCH_VERSION)) {
			return true;
		} else {
			getProject().getLogger().lifecycle(":forge patched jars not up to date. current version: " + value);
			return false;
		}
	}

	private void accessTransformForge(File input, Logger logger) throws Exception {
		Stopwatch stopwatch = Stopwatch.createStarted();

		logger.lifecycle(":access transforming minecraft");

		File target = minecraftMergedPatchedSrgAtJar;
		Files.deleteIfExists(target.toPath());

		File tmpFile = null;

		try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + getForgeUserdevJar().toURI()), ImmutableMap.of("create", false))) {
			for (JsonElement at : getExtension().getForgeUserdevProvider().getConfig().getAsJsonArray("ats")) {
				if (tmpFile == null) {
					tmpFile = File.createTempFile("at-conf", ".cfg");
				}

				Files.write(tmpFile.toPath(), Files.readAllBytes(fs.getPath(at.getAsString())), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			}
		}

		if (tmpFile != null) {
			File finalTmpFile = tmpFile;
			AccessTransformerJarProcessor.executeAt(getProject(), input.toPath(), target.toPath(), args -> {
				args.add("--atFile");
				args.add(finalTmpFile.getAbsolutePath());
			});
			tmpFile.delete();
		}

		logger.lifecycle(":access transformed minecraft in " + stopwatch.stop());
	}

	private void remapPatchedJar(Logger logger) throws Exception {
		getProject().getLogger().lifecycle(":remapping minecraft (TinyRemapper, srg -> official)");
		Path mcInput = minecraftMergedPatchedSrgAtJar.toPath();
		Path mcOutput = minecraftMergedPatchedAtJar.toPath();
		Files.deleteIfExists(mcOutput);

		TinyRemapper remapper = buildRemapper(mcInput);

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(mcOutput).build()) {
			outputConsumer.addNonClassFiles(mcInput);
			remapper.readInputs(mcInput);
			remapper.apply(outputConsumer);
		} finally {
			remapper.finish();
		}

		applyLoomPatchVersion(mcOutput);
	}

	private void patchJar(File input, Logger logger) throws IOException {
		Stopwatch stopwatch = Stopwatch.createStarted();
		logger.lifecycle(":patching jar");

		PatchProvider patchProvider = getExtension().getPatchProvider();
		patchJar(input, minecraftMergedPatchedSrgJar, patchProvider.patches);

		try {
			copyMissingClasses(input, minecraftMergedPatchedSrgJar);
			deleteParameterNames(minecraftMergedPatchedSrgJar);

			if (getExtension().isForgeAndNotOfficial()) {
				fixParameterAnnotation(minecraftMergedPatchedSrgJar);
			}
		} catch (Exception exception) {
			throw new RuntimeException(exception);
		}

		logger.lifecycle(":patched jar in " + stopwatch.stop());
	}

	private void patchJar(File clean, File output, Path patches) throws IOException {
		PrintStream previous = System.out;

		try {
			System.setOut(new PrintStream(NullOutputStream.NULL_OUTPUT_STREAM));
		} catch (SecurityException ignored) {
			// Failed to replace logger filter, just ignore
		}

		ConsoleTool.main(new String[] {
				"--clean", clean.getAbsolutePath(),
				"--output", output.getAbsolutePath(),
				"--apply", patches.toAbsolutePath().toString()
		});

		try {
			System.setOut(previous);
		} catch (SecurityException ignored) {
			// Failed to replace logger filter, just ignore
		}
	}

	private void mergeJars(Logger logger) throws IOException {
		getExtension().getMcpConfigProvider().getMergeAction().execute(getMinecraftClientJar().toPath(), getEffectiveServerJar().toPath(), minecraftVersion(), minecraftMergedJar.toPath());

		Path stripped = SpecialSourceExecutor.stripJar(getProject(), minecraftMergedJar.toPath(), getToSrgMappings());
		Files.deleteIfExists(minecraftMergedJar.toPath());
		Files.copy(stripped, minecraftMergedJar.toPath());
	}

	private void walkFileSystems(File source, File target, Predicate<Path> filter, Function<FileSystem, Iterable<Path>> toWalk, FsPathConsumer action)
			throws IOException {
		try (FileSystemUtil.Delegate sourceFs = FileSystemUtil.getJarFileSystem(source, false);
				FileSystemUtil.Delegate targetFs = FileSystemUtil.getJarFileSystem(target, false)) {
			for (Path sourceDir : toWalk.apply(sourceFs.get())) {
				Path dir = sourceDir.toAbsolutePath();
				if (!Files.exists(dir)) continue;
				Files.walk(dir)
						.filter(Files::isRegularFile)
						.filter(filter)
						.forEach(it -> {
							boolean root = dir.getParent() == null;

							try {
								Path relativeSource = root ? it : dir.relativize(it);
								Path targetPath = targetFs.get().getPath(relativeSource.toString());
								action.accept(sourceFs.get(), targetFs.get(), it, targetPath);
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						});
			}
		}
	}

	private void walkFileSystems(File source, File target, Predicate<Path> filter, FsPathConsumer action) throws IOException {
		walkFileSystems(source, target, filter, FileSystem::getRootDirectories, action);
	}

	private void copyMissingClasses(File source, File target) throws IOException {
		walkFileSystems(source, target, it -> it.toString().endsWith(".class"), (sourceFs, targetFs, sourcePath, targetPath) -> {
			if (Files.exists(targetPath)) return;
			Path parent = targetPath.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Files.copy(sourcePath, targetPath);
		});
	}

	private void copyNonClassFiles(File source, File target) throws IOException {
		Predicate<Path> filter = file -> {
			String s = file.toString();
			return !s.endsWith(".class") && !s.startsWith("/META-INF");
		};

		walkFileSystems(source, target, filter, this::copyReplacing);
	}

	private void copyReplacing(FileSystem sourceFs, FileSystem targetFs, Path sourcePath, Path targetPath) throws IOException {
		Path parent = targetPath.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
	}

	private void copyUserdevFiles(File source, File target) throws IOException {
		// Removes the Forge name mapping service definition so that our own is used.
		// If there are multiple name mapping services with the same "understanding" pair
		// (source -> target namespace pair), modlauncher throws a fit and will crash.
		// To use our YarnNamingService instead of MCPNamingService, we have to remove this file.
		Predicate<Path> filter = file -> !file.toString().equals(NAME_MAPPING_SERVICE_PATH);

		walkFileSystems(source, target, filter, fs -> Collections.singleton(fs.getPath("inject")), (sourceFs, targetFs, sourcePath, targetPath) -> {
			Path parent = targetPath.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Files.copy(sourcePath, targetPath);
		});
	}

	public void applyLoomPatchVersion(Path target) throws IOException {
		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(target, false)) {
			Path manifestPath = delegate.get().getPath("META-INF/MANIFEST.MF");

			Preconditions.checkArgument(Files.exists(manifestPath), "META-INF/MANIFEST.MF does not exist in patched srg jar!");
			Manifest manifest = new Manifest();

			if (Files.exists(manifestPath)) {
				try (InputStream stream = Files.newInputStream(manifestPath)) {
					manifest.read(stream);
					manifest.getMainAttributes().putValue(LOOM_PATCH_VERSION_KEY, CURRENT_LOOM_PATCH_VERSION);
				}
			}

			try (OutputStream stream = Files.newOutputStream(manifestPath, StandardOpenOption.CREATE)) {
				manifest.write(stream);
			}
		}
	}

	public File getMinecraftMergedPatchedJar() {
		return minecraftMergedPatchedSrgJar;
	}

	@Override
	public Path getMergedJar() {
		return minecraftMergedPatchedAtJar.toPath();
	}

	@Override
	public List<Path> getMinecraftJars() {
		return List.of(minecraftMergedPatchedAtJar.toPath());
	}
}
