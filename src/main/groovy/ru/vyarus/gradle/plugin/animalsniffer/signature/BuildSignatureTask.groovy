package ru.vyarus.gradle.plugin.animalsniffer.signature

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.ConventionTask
import org.gradle.api.internal.project.IsolatedAntBuilder
import org.gradle.api.tasks.*
import org.gradle.util.GFileUtils
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferPlugin

import javax.inject.Inject

/**
 * Task for building animalsniffer signature. Compiled classes, jars and other signatures may be used as source.
 * <p>
 * Task may be used directly or through registered `animalsnifferSignature' configuration closure.
 * <p>
 * AnimalsnifferClasspath will be set from 'animalsniffer' configuration. By default, output file
 * is `build/animalsniffer/${task.name}/${task.name}.sig`. In some cases multiple signatures could be produces,
 * so use {@code task.outputFiles} method to get correct task output.
 * <p>
 * Properties may be used for configuration directly, but it will be more convenient to use provided helper
 * methods instead.
 * <p>
 * Animalsniffer ant task does not allow empty files, but, in some cases, it could happen.
 * For example, when two or more signatures must be merged. In this case plugin will
 * specify fake file together with it's exclusion from resulted signature to overcome ant task limitation.
 *
 * @author Vyacheslav Rusakov
 * @since 12.04.2017
 * @see AnimalSnifferSignatureExtension for configuration extension
 * @see <a href="http://www.mojohaus.org/animal-sniffer/animal-sniffer-ant-tasks/examples/generating-signatures.html">
 *     ant task docks</a>
 */
@CompileStatic
@CacheableTask
@SuppressWarnings('ConfusingMethodName')
class BuildSignatureTask extends ConventionTask {

    /**
     * Symbol used when {@link #mergeSignatures} disabled and so multiple signatures produced (according to input
     * signatures count). This is used for signature caching for check task so it could print real signature name
     * in output without cache signature name.
     */
    public static final String SIGNATURE_DELIMITER = '_!'
    private static final String DOT = '.'

    /**
     * The class path containing the Animal Sniffer library to be used.
     * Default set by plugin.
     */
    @Classpath
    @InputFiles
    FileCollection animalsnifferClasspath

    /**
     * Signature files to extend.
     */
    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.ABSOLUTE)
    FileCollection signatures

    /**
     * Compiled classes and jars.
     * Ant task requires files, but plugin will overcome this with fake files
     * ({@see # allowEmptyFiles)
     */
    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.ABSOLUTE)
    FileCollection files = project.files()

    /**
     * Include only packages ('com.java.*')
     */
    @Input
    @Optional
    Set<String> include = []

    /**
     * Exclude packages ('com.sun.*')
     */
    @Input
    @Optional
    Set<String> exclude = []

    /**
     * Output signatures directory.
     */
    @OutputDirectory
    File outputDirectory

    /**
     * Output signature name. If extension is not set then '.sig' extension will be used. By default, equal to
     * task name (set by plugin).
     * <p>
     * Note: when {@link #mergeSignatures} is false, provided name is used as base to create multiple names
     * (by adding _!signatureName postfix).
     */
    @Input
    String outputName

    /**
     * Animalsniffer ant task does not allow empty files, but, in some cases, it could happen.
     * For example, when two or more signatures must be merged. In this case plugin could
     * specify fake file together with it's exclusion from resulted signature to overcome ant task limitation.
     * <p>
     * Option exists only to be able to switch off empty files workaround. It may be required due to
     * security restrictions which could lead to source jar resolution failure (not the case for most environments).
     */
    @Input
    boolean allowEmptyFiles = true

    /**
     * When multiple signatures provided they will be merged. If merge disabled, multiple signatures will be provided
     * for each incoming signature.
     * <p>
     * This is very special case used internally for classpath caching when multiple signatures used for check task.
     */
    @Input
    boolean mergeSignatures = true

    @SuppressWarnings('UnnecessaryGetter')
    BuildSignatureTask() {
        // task-specific output directory used to avoid clashes when multiple build tasks used
        // (output files are taken from directory, so if multiple tasks used, last task output will include
        // all tasks outputs, which is wrong).
        conventionMapping.map('outputDirectory') { getDefaultTaskOutputDirectory() }
    }

    @Inject
    IsolatedAntBuilder getAntBuilder() {
        throw new UnsupportedOperationException()
    }

    @TaskAction
    void run() {
        if (handleSimpleCase()) {
            return
        }
        applyFakeFilesIfRequired()
        if (!getInclude().empty || !getExclude().empty) {
            logger.info("Building signature with includes=${getInclude()} and excludes=${getExclude()}")
        }

        Set<File> signs = getSignatures() ? getSignatures().files : [] as Set<File>
        if (getMergeSignatures() || signs.size() <= 1) {
            runSignatureBuild(signs, targetFile)
        } else {
            // use each provided signature as base (provide multiple signatures)
            signs.each {
                runSignatureBuild([it], getPerSignatureTargetFile(it))
            }
        }
    }

    /**
     * Files to use for signature generation. Includes classes (compiled) or jars.
     * Internally {@link org.gradle.api.Project#files(java.lang.Object ...)} is used so any definition may be used,
     * supported by gradle method.
     * <p>
     * Method may be called multiple times - all inputs will aggregate.
     * <p>
     * Common cases:
     * <ul>
     * <li>sourceSets.main.output - all compiled classes of source set</li>
     * <li>configurations.mycustom - all jars, defined in 'mycustom' configuration (of course, standard
     * configurations like 'compile' or 'test' could also be used)</li>
     * <ul>
     *
     * @param input input files (classes ot jars)
     */
    void files(Object input) {
        if (getFiles() == null) {
            setFiles(project.files(input))
        } else {
            setFiles(getFiles() + project.files(input))
        }
    }

    /**
     * Signatures to extend from (use all definitions from signature). May be used only together with files definition
     * (for example, you can't just merge multiple signatures; this is ant task limitation).
     * <p>
     * Method may be called multiple times - all inputs will aggregate.
     * <p>
     * Plugin register special 'signature' configuration to use for signatures definition and it makes sense to use
     * it for signatures definition for build task. In this case, use {@code configurations.signature} to add
     * all defined signatures.
     * <p>
     * Internally {@link org.gradle.api.Project#files(java.lang.Object ...)} is used so any definition may be used,
     * supported by gradle method.
     *
     * @param signature base signatures
     */
    void signatures(Object signature) {
        if (getSignatures() == null) {
            setSignatures(project.files(signature))
        } else {
            setSignatures(getSignatures() + project.files(signature))
        }
    }

    /**
     * Use to include only defined classes in resulted signature.
     * For example: specify 'com.java.*' to include only 'com.java' package and all it's sub packages.
     * <p>
     * Method may be called multiple times - all inputs will aggregate.
     *
     * @param path class names or package mask
     */
    void include(String... path) {
        Set<String> include = getInclude()
        include.addAll(path)
        setInclude(include)
    }

    /**
     * Use to exclude classes in resulted signature.
     * For example: specify 'com.sun.*' to exclude 'com.sun' package and all it's sub packages.
     * <p>
     * Method may be called multiple times - all inputs will aggregate.
     *
     * @param path class names or package mask
     */
    void exclude(String... path) {
        Set<String> exclude = getExclude()
        exclude.addAll(path)
        setExclude(exclude)
    }

    /**
     * It is intentional that property is not annotated with {@code @OutputFiles}!
     * <p>
     * Task output depends on inputs (number of produced signatures and their names rely on
     * configured signatures). Since gradle 4 task output property can't force dependency resolution,
     * but in most cases input signatures are configured with 'signature' configuration. This makes impossible
     * computation of exact output file names before execution (such computation was implemented in plugin version 1.4).
     * <p>
     *  Due to this limitation, task declares only output directory as task output. But that means that
     * {@code task.outputs.files} can't be used, because it will contain only output directory (itself).
     *  But it's a very handy to rely on task output files, and this method should be used instead of
     *  outputs mechanism ({@code task.outputFiles}) to configure produced signatures in other tasks.
     *
     * @return lazy collection of resulted signatures to use as input for other tasks
     */
    @Internal
    FileCollection getOutputFiles() {
        return project.fileTree(getOutputDirectory()).builtBy(this)
    }

    /**
     * Note that in case of check task, this will never be true because exclusions are always applied for cache
     * task (see {@link ru.vyarus.gradle.plugin.animalsniffer.CheckCacheExtension#exclude}).
     *
     * @return true when no processing required for configured signatures
     */
    private boolean handleSimpleCase() {
        if (getFiles().empty
                && (getSignatures().size() == 1 || !getMergeSignatures())
                && getExclude().empty && getInclude().empty) {
            getSignatures().each {
                GFileUtils.copyFile(it, getMergeSignatures() ? targetFile : getPerSignatureTargetFile(it))
            }
            return true
        }
        return false
    }

    private void applyFakeFilesIfRequired() {
        boolean signaturesConfigured = getSignatures() != null && !getSignatures().empty
        // situation: files specified, but collection is empty (for example, empty configuration)
        if (getFiles().empty && signaturesConfigured && isAllowEmptyFiles()) {
            logger.info('Workaround empty files for signature build by adding plugin jar as file ' +
                    'with package exclusion')
            // if this will fail due to security restrictions, simply disable allowEmptyFiles config
            files(BuildSignatureTask.protectionDomain.codeSource.location)
            exclude('ru.vyarus.gradle.plugin.animalsniffer.*')
        } else if (getFiles().empty) {
            throw new GradleException("No files found in: ${getFiles()}")
        }
    }

    /**
     * By default task name used for output directory.
     * For cache tasks, 'animalsniffer' prefix cut off.
     *
     * @return default output directory
     */
    private File getDefaultTaskOutputDirectory() {
        String subDir = name
        if (subDir.startsWith(AnimalSnifferPlugin.ANIMALSNIFFER_CACHE)) {
            subDir = "cache${subDir[AnimalSnifferPlugin.ANIMALSNIFFER_CACHE.length()..-1]}"
        }
        return new File(project.buildDir, "animalsniffer/$subDir")
    }

    private String getSignatureFileName() {
        String out = getOutputName()
        if (outputName.indexOf(DOT) < 0) {
            out += '.sig'
        }
        return out
    }

    /**
     * @return output signature file for merge mode ({@link #mergeSignatures} enabled)
     */
    private File getTargetFile() {
        return new File(getOutputDirectory(), signatureFileName)
    }

    /**
     * When {@link #mergeSignatures} is disabled, task may produce multiple signatures
     * for each source signature. Output file name is build by convention:
     * configured signature name + special delimiter (to be able to recognize it) +
     * source signature name.
     *
     * @param sig signature file
     * @return output signature file name for specific source signature
     */
    private File getPerSignatureTargetFile(File sig) {
        // use each provided signature as base (provide multiple signatures)
        String out = signatureFileName
        String baseName = out[0..out.lastIndexOf(DOT) - 1]
        String ext = out[out.lastIndexOf(DOT) + 1..-1]

        String name = sig.name[0..sig.name.lastIndexOf(DOT) - 1]
        return new File(getOutputDirectory(), "${baseName}${SIGNATURE_DELIMITER}$name.$ext")
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    private void runSignatureBuild(Collection<File> signatures, File dest) {
        antBuilder.withClasspath(getAnimalsnifferClasspath()).execute { a ->
            ant.taskdef(name: 'buildSignature', classname: 'org.codehaus.mojo.animal_sniffer.ant.BuildSignaturesTask')
            ant.buildSignature(destfile: dest.absolutePath) {
                if (getFiles() && !getFiles().empty) {
                    path(path: getFiles().asPath)
                }
                signatures.each {
                    signature(src: it.absolutePath)
                }
                getInclude().each {
                    includeClasses(className: it)
                }
                getExclude().each {
                    excludeClasses(className: it)
                }
            }
        }
    }
}
