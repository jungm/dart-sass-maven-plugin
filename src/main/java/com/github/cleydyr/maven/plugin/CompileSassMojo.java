package com.github.cleydyr.maven.plugin;

import com.github.cleydyr.dart.embedded.MavenLoggingHandler;
import com.github.cleydyr.dart.command.enums.SourceMapURLs;
import com.github.cleydyr.dart.command.enums.Style;
import com.github.cleydyr.dart.net.GithubLatestVersionProvider;
import com.github.cleydyr.dart.release.DartSassReleaseParameter;
import com.github.cleydyr.dart.system.OSDetector;
import com.github.cleydyr.dart.system.io.DartSassExecutableExtractor;
import com.github.cleydyr.dart.system.io.DefaultCachedFilesDirectoryProviderFactory;
import com.github.cleydyr.dart.system.io.factory.DartSassExecutableExtractorFactory;
import com.github.cleydyr.dart.system.io.utils.SystemUtils;
import com.sass_lang.embedded_protocol.OutputStyle;
import de.larsgrefer.sass.embedded.CompileSuccess;
import de.larsgrefer.sass.embedded.SassCompilationFailedException;
import de.larsgrefer.sass.embedded.SassCompiler;
import de.larsgrefer.sass.embedded.connection.ConnectionFactory;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.MavenSettingsBuilder;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Goal that compiles a set of sass/scss files from an input directory to an output directory.
 */
@SuppressWarnings("deprecation")
@Mojo(name = "compile-sass", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, threadSafe = true)
public class CompileSassMojo extends AbstractMojo {
    protected DartSassExecutableExtractorFactory dartSassExecutableExtractorFactory;

    protected GithubLatestVersionProvider githubLatestVersionProvider;

    protected Supplier<File> cachedFilesDirectoryProvider;

    private DartSassReleaseParameter dartSassReleaseParameter;

    private URL proxyHost;

    private static final Object LOCK = new Object();

    @Inject
    public CompileSassMojo(
            DartSassExecutableExtractorFactory dartSassExecutableExtractorFactory,
            GithubLatestVersionProvider githubLatestVersionProvider,
            DefaultCachedFilesDirectoryProviderFactory cachedFilesDirectoryProviderFactory,
            MavenSettingsBuilder mavenSettingsBuilder) {
        this.dartSassExecutableExtractorFactory = dartSassExecutableExtractorFactory;
        this.githubLatestVersionProvider = githubLatestVersionProvider;
        this.cachedFilesDirectoryProvider = cachedFilesDirectoryProviderFactory.get();

        if (mavenSettingsBuilder == null) {
            return;
        }

        try {
            Settings settings = mavenSettingsBuilder.buildSettings();

            Proxy activeProxy = settings.getActiveProxy();

            if (activeProxy == null) {
                return;
            }

            String host = activeProxy.getHost();

            int port = activeProxy.getPort();

            String protocol = activeProxy.getProtocol();

            proxyHost = new URL(protocol + "://" + host + ":" + port);
        } catch (IOException | XmlPullParserException e) {
            getLog().warn("Error while parsing maven settings. Settings like proxy will be ignored.");
        }
    }

    /**
     * Path to the folder where the sass/scss are located.
     */
    @Parameter(defaultValue = "src/main/sass")
    private File inputFolder;

    /**
     * Path to the folder where the css and source map files will be created.
     */
    @Parameter(property = "project.build.directory")
    private File outputFolder;

    /**
     * Paths from where additional load path for Sass to look for stylesheets will be loaded. It can
     * be passed multiple times to provide multiple load paths. Earlier load paths will take
     * precedence over later ones.
     */
    @Parameter
    private List<File> loadPaths;

    /**
     * This option controls the output style of the resulting CSS. Dart Sass supports two output
     * styles:<br>
     * <ul>
     * <li>expanded (the default), which writes each selector and declaration on its own line; and
     * </li>
     * <li>compressed, which removes as many extra characters as possible, and writes the entire
     * stylesheet on a single line.</li>
     * </ul>
     * Use either <code>EXPANDED</code> or <code>COMPRESSED</code> in the plugin configuration.
     */
    @Parameter(defaultValue = "EXPANDED")
    private Style style;

    /**
     * This flag tells Sass never to emit a <code>@charset</code> declaration or a UTF-8 byte-order
     * mark. By default, or if the <code>charset</code> flag is activated, Sass will insert either a
     * <code>@charset</code> declaration (in expanded output mode) or a byte-order mark (in
     * compressed output mode) if the stylesheet contains any non-ASCII characters.
     */
    @Parameter(defaultValue = "false")
    private boolean noCharset;

    /**
     * This flag tells Sass whether to emit a CSS file when an error occurs during compilation.
     * This CSS file describes the error in a comment and in the content property of <code>
     * body::before,</code> so that you can see the error message in the browser without needing to
     * switch back to the terminal.<br>
     * By default, error CSS is enabled if you’re compiling to at least one file on disk (as opposed
     * to standard output). You can activate <code>errorCSS</code> explicitly to enable it even when
     * you’re compiling to standard out (not supported by this Maven plugin), or set it explicitly
     * to <code>false</code> to disable it everywhere. When it’s disabled, the <code>update</code>
     * flag and <code>watch</code> flag (the latter being not yet supported by this Maven plugin)
     * will delete CSS files instead when an error occurs.
     */
    @Parameter(defaultValue = "true")
    private boolean errorCSS;

    /**
     * If the this flag is set to <code>true</code>, Sass will only compile stylesheets whose
     * dependencies have been modified more recently than the corresponding CSS file was generated.
     * It will also print status messages when updating stylesheets.
     */
    @Parameter(defaultValue = "false")
    private boolean update;

    /**
     * If the <code>noSourceMap</code> flag is set to <code>true</code>, Sass won’t generate any
     * source maps. It cannot be passed along with other source map options (namely <code>
     * sourceMapURLs</code>, <code>embedSources</code> and <code>embedSourceMap</code>
     */
    @Parameter(defaultValue = "false")
    private boolean noSourceMap;

    /**
     * This option controls how the source maps that Sass generates link back to the Sass files that
     * contributed to the generated CSS. Dart Sass supports two types of URLs:
     * <ul>
     * <li> relative (the default) uses relative URLs from the location of the source map file to
     * the locations of the Sass source file;</li> and
     * <li>absolute uses the absolute file: URLs of the Sass source files. Note that absolute URLs
     * will only work on the same computer that the CSS was compiled.</li>
     * </ul>
     * Use either <code>RELATIVE</code> or <code>ABSOLUTE</code> in the plugin configuration.
     */
    @Parameter(defaultValue = "RELATIVE")
    private SourceMapURLs sourceMapURLs;

    /**
     * This flag tells Sass to embed the entire contents of the Sass files that contributed to the
     * generated CSS in the source map. This may produce very large source maps, but it guarantees
     * that the source will be available on any computer no matter how the CSS is served.
     */
    @Parameter(defaultValue = "false")
    private boolean embedSources;

    /**
     * This flag tells Sass to embed the contents of the source map file in the generated CSS,
     * rather than creating a separate file and linking to it from the CSS.
     */
    @Parameter(defaultValue = "false")
    private boolean embedSourceMap;

    /**
     * This flag tells Sass to stop compiling immediately when an error is detected, rather than
     * trying to compile other Sass files that may not contain errors. It’s mostly useful in
     * many-to-many mode (which is the mode currently supported by this Maven plugin).
     */
    @Parameter(defaultValue = "false")
    private boolean stopOnError;

    /**
     * This flag tells Sass to emit terminal colors. By default, it will emit colors if it looks
     * like it’s being run on a terminal that supports them. Set it to <code>false</code> to tell
     * Sass to not emit colors.
     */
    @Parameter(defaultValue = "true")
    private boolean color;

    /**
     * This flag tells Sass only to emit ASCII characters to the terminal as part of error messages.
     * By default, Sass will emit non-ASCII characters for these messages. This flag does not affect
     * the CSS output.
     */
    @Parameter(defaultValue = "false")
    private boolean noUnicode;

    /**
     * This flag tells Sass not to emit any warnings when compiling. By default, Sass emits warnings
     * when deprecated features are used or when the <code>@warn</code> rule is encountered. It also
     * silences the <code>@debug</code> rule.
     */
    @Parameter(defaultValue = "false")
    private boolean quiet;

    /**
     * This flag tells Sass not to emit deprecation warnings that come from dependencies. It
     * considers any file that’s transitively imported through a load path to be a “dependency”.
     * This flag doesn’t affect the <code>@warn</code> rule or the <code>@debug</code> rule.
     */
    @Parameter(defaultValue = "false")
    private boolean quietDeps;

    /**
     * This flag tells Sass to print the full Dart stack trace when an error is encountered. It’s
     * used by the Sass team for debugging errors.
     */
    @Parameter(defaultValue = "false")
    private boolean trace;

    /**
     * This parameter represents the Dart Sass version that should be used to compile Sass files.
     * If left unset, the version available at https://github.com/sass/dart-sass/releases/latest
     * will be used.
     */
    @Parameter
    private String version;

    /**
     * This parameter represents the Dart Sass architecture that should be used to compile Sass
     * files. If letf unset, it will be autodetected by the plugin. Accepted values are
     * "x64", "aarch32", "aarch64" and "ia32".
     */
    @Parameter
    private String arch;

    /**
     * This parameter represents the Dart Sass operating system that should be used to compile
     * Sass files. If letf unset, it will be autodetected by the plugin. Accepted values are
     * "linux", "macos" and "windows".
     */
    @Parameter
    private String os;

    /**
     * This parameter represents a path in the local file system where the release archive
     * downloaded from the internet will stored. If letf unset, it will default to
     * <ul>
     *  <li><code>$HOME/.cache/dart-sass-maven-plugin/</code> on *nix operating systems; or</li>
     *  <li><code>%LOCALAPPDATA%\dart-sass-maven-plugin\Cache</code> on Windows operating systems.</li>
     * </ul>
     */
    @Parameter
    private File cachedFilesDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        validateProxyHostSyntax();

        verifyDefaultParameters();

        unsetIncompatibleOptions();

        synchronized (LOCK) {
            extractExecutable();
        }

        boolean success = true;
        try (SassCompiler compiler = createSassCompiler()) {
            Set<Path> candidates = Files.walk(inputFolder.toPath()).collect(Collectors.toSet());

            long start = System.currentTimeMillis();
            int count = 0;
            for (Path inputPath : candidates) {
                String inputFileName = inputPath.toFile().getName();
                if (!inputFileName.endsWith(".scss") && !inputFileName.endsWith(".sass") && !inputFileName.endsWith(".css")) {
                    getLog().debug("Skipped processing " + inputPath + ", unknown file extension");
                    continue;
                }

                Path outputPath = getOutputPath(inputPath);
                if (!isCompilationRequired(inputPath, outputPath)) {
                    getLog().debug("Skipped " + inputPath + ", compilation is not required");
                    continue;
                }

                try {
                    compileStylesheet(compiler, inputPath, outputPath);

                    count++;
                } catch (SassCompilationFailedException compilationFailedException) {
                    if (stopOnError) {
                        throw new MojoExecutionException(compilationFailedException);
                    }

                    getLog().error("Compiling " + inputFileName + " failed", compilationFailedException);
                    success = false;
                }
            }

            getLog().info("Compiled " + count + " files in " + (System.currentTimeMillis() - start) + "ms");
        } catch (IOException e) {
            throw new MojoExecutionException(e);
        }

        if (!success) {
            throw new MojoFailureException("Execution failed, see individual errors above");
        }
    }

    protected Path getOutputPath(Path inputPath) {
        Path realtiveInputPath = inputFolder.toPath().relativize(inputPath);
        return outputFolder.toPath().resolve(
                Path.of(realtiveInputPath.toString()
                        .replace(".scss", ".css")
                        .replace(".sass", ".css")));
    }

    protected boolean isCompilationRequired(Path inputPath, Path outputPath) {
        if (!update || !outputPath.toFile().exists()) {
            return true;
        }

        return true; // TODO handle update=true here (probably with some sort of dependency tree to cascade updates)
    }

    protected void compileStylesheet(SassCompiler compiler, Path input, Path output) throws IOException, SassCompilationFailedException {
        long start = System.currentTimeMillis();
        getLog().debug("Begin compiling " + input);
        CompileSuccess compileSuccess = compiler.compileFile(input.toFile(), OutputStyle.valueOf(style.name()));

        // Ensure parent directory of output file exists
        output.getParent().toFile().mkdirs();

        String css = compileSuccess.getCss();
        if (!noCharset && !StandardCharsets.US_ASCII.newEncoder().canEncode(css)) {
            css = addEncodingPrefix(css);
        }

        if (!noSourceMap) {
            css = addSourceMappingUrl(css, compileSuccess.getSourceMap(), input, output);
        }

        Files.writeString(output, css);

        getLog().info("Compiled " + input + " to " + output + " in " + (System.currentTimeMillis() - start) + "ms");
    }

    /**
     * Adds a source mapping URL to the provided CSS content. Note that if embedSourceMap=false,
     * this function will also write a .map file containing the source map
     */
    protected String addSourceMappingUrl(String css, String sourceMap, Path inputPath, Path outputPath) throws IOException {
        // ABSOLUTE is generated by default, when sourceMapURLs=RELATIVE it has to be rewritten
        if (sourceMapURLs == SourceMapURLs.RELATIVE) {
            sourceMap = sourceMap.replaceAll(
                    inputPath.toString(),
                    outputPath.getParent().relativize(inputPath).toString());
        }

        // Drop file:// protocol (automatically added by sass compiler)
        sourceMap = sourceMap.replaceAll("file://", "");

        String sourceMapUrl;
        if (embedSourceMap) {
            sourceMapUrl = "data:application/json;charset=utf-8," + URLEncoder.encode(sourceMap, StandardCharsets.UTF_8);
        } else {
            sourceMapUrl = outputPath.getFileName().toString() + ".map";
            Files.writeString(outputPath.getParent().resolve(sourceMapUrl), sourceMap);
        }

        return css + "/*# sourceMappingUrl=" + sourceMapUrl + " */";
    }

    /**
     * Adds an "encoding prefix" to the provided CSS like dart-sass CLI would do, this is either:
     * <ul>
     *     <li>A UTF-8 byte order mark (\ufeff) if style=COMPRESSED</li>
     *     <li>An <code>@charset "UTF-8";</code> CSS rule if style=EXPANDED</li>
     * </ul>
     */
    protected String addEncodingPrefix(String css) {
        if (style == Style.EXPANDED) {
            return "@charset \"UTF-8\"" + System.lineSeparator() + css;
        } else {
            return "\ufeff" + css;
        }
    }

    protected void verifyDefaultParameters() throws MojoExecutionException {
        if (os == null) {
            os = OSDetector.getOSName();
            getLog().info("Auto-detected operating system: " + os);
        } else if (!OSDetector.isAcceptedOSName(os)) {
            getLog().warn("os value " + os + " is not among the accepted values: "
                    + OSDetector.ACCEPTED_OSES.toString());
        }

        if (arch == null) {
            arch = OSDetector.getOSArchitecture();

            getLog().info("Auto-detected operating system architecture: " + arch);
        } else if (!OSDetector.isAcceptedArchitecture(arch)) {
            getLog().warn("architecture value " + arch + " is not among the accepted values: "
                    + OSDetector.ACCEPTED_ARCHITECTURES.toString());
        }

        if (version == null) {
            version = githubLatestVersionProvider.get();

            getLog().info("Auto-detected latest version: " + version);
        }

        if (cachedFilesDirectory == null) {
            cachedFilesDirectory = cachedFilesDirectoryProvider.get();

            getLog().info("Auto-detected cached files directory: " + cachedFilesDirectory);
        }

        dartSassReleaseParameter = new DartSassReleaseParameter(os, arch, version);
    }

    public void unsetIncompatibleOptions() {
        if (noSourceMap) {
            sourceMapURLs = null;
            embedSourceMap = false;
            embedSources = false;
        }
    }

    public void extractExecutable() throws MojoExecutionException {
        DartSassExecutableExtractor dartSassExecutableExtractor =
                dartSassExecutableExtractorFactory.getDartSassExecutableExtractor(
                        dartSassReleaseParameter, cachedFilesDirectory, proxyHost);

        try {
            dartSassExecutableExtractor.extract();
        } catch (Exception exception) {
            throw new MojoExecutionException("Unable to extract sass executable", exception);
        }
    }

    private void validateProxyHostSyntax() throws MojoExecutionException {
        if (proxyHost == null) {
            return;
        }

        try {
            proxyHost.toURI();
        } catch (URISyntaxException e) {
            throw new MojoExecutionException(e);
        }
    }

    protected SassCompiler createSassCompiler() throws IOException {
        Path tmpDir = SystemUtils.getExecutableTempFolder(dartSassReleaseParameter);
        File sassExecutable = OSDetector.isWindows()
                ? tmpDir.resolve("sass.bat").toFile()
                : tmpDir.resolve("sass").toFile();

        SassCompiler compiler = new SassCompiler(ConnectionFactory.ofExecutable(sassExecutable));
        if (loadPaths != null) {
            compiler.setLoadPaths(loadPaths);
        }

        compiler.setSourceMapIncludeSources(embedSources);
        compiler.setGenerateSourceMaps(!noSourceMap);
        compiler.setQuietDeps(quietDeps);
        compiler.setLoggingHandler(new MavenLoggingHandler(getLog(), quiet));

        return compiler;
    }

    public File getInputFolder() {
        return inputFolder;
    }

    public void setInputFolder(File inputFolder) {
        this.inputFolder = inputFolder;
    }

    public File getOutputFolder() {
        return outputFolder;
    }

    public void setOutputFolder(File outputFolder) {
        this.outputFolder = outputFolder;
    }

    public List<File> getLoadPaths() {
        return loadPaths;
    }

    public void setLoadPaths(List<File> loadPaths) {
        this.loadPaths = loadPaths;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style;
    }

    public boolean isNoCharset() {
        return noCharset;
    }

    public void setNoCharset(boolean noCharset) {
        this.noCharset = noCharset;
    }

    public boolean isErrorCSS() {
        return errorCSS;
    }

    public void setErrorCSS(boolean errorCSS) {
        this.errorCSS = errorCSS;
    }

    public boolean isUpdate() {
        return update;
    }

    public void setUpdate(boolean update) {
        this.update = update;
    }

    public boolean isNoSourceMap() {
        return noSourceMap;
    }

    public void setNoSourceMap(boolean noSourceMap) {
        this.noSourceMap = noSourceMap;
    }

    public SourceMapURLs getSourceMapURLs() {
        return sourceMapURLs;
    }

    public void setSourceMapURLs(SourceMapURLs sourceMapURLs) {
        this.sourceMapURLs = sourceMapURLs;
    }

    public boolean isEmbedSources() {
        return embedSources;
    }

    public void setEmbedSources(boolean embedSources) {
        this.embedSources = embedSources;
    }

    public boolean isEmbedSourceMap() {
        return embedSourceMap;
    }

    public void setEmbedSourceMap(boolean embedSourceMap) {
        this.embedSourceMap = embedSourceMap;
    }

    public boolean isStopOnError() {
        return stopOnError;
    }

    public void setStopOnError(boolean stopOnError) {
        this.stopOnError = stopOnError;
    }

    public boolean isColor() {
        return color;
    }

    public void setColor(boolean color) {
        this.color = color;
    }

    public boolean isNoUnicode() {
        return noUnicode;
    }

    public void setNoUnicode(boolean noUnicode) {
        this.noUnicode = noUnicode;
    }

    public boolean isQuiet() {
        return quiet;
    }

    public void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    public boolean isQuietDeps() {
        return quietDeps;
    }

    public void setQuietDeps(boolean quietDeps) {
        this.quietDeps = quietDeps;
    }

    public boolean isTrace() {
        return trace;
    }

    public void setTrace(boolean trace) {
        this.trace = trace;
    }
}
