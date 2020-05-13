package com.github.hlls.transform;

import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.Format;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Status;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider;
import com.android.build.gradle.AppExtension;
import com.android.build.gradle.BaseExtension;
import com.android.build.gradle.LibraryExtension;
import com.android.ide.common.internal.WaitableExecutor;
import com.android.utils.FileUtils;
import com.google.common.collect.ImmutableSet;

import org.apache.http.util.TextUtils;
import org.gradle.api.Project;
import org.gradle.api.plugins.ExtensionContainer;
import org.zeroturnaround.zip.ZipUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import javassist.CannotCompileException;
import javassist.ClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.NotFoundException;

/**
 * @author <a href="mailto:249418416@qq.com">mapleleaf</a>
 */
public abstract class EasyTransform extends Transform {

    private ClassPool mClassPool = new ClassPool(true);
    protected Project mProject;
    protected CurrentScope mCurrentScope;
    private Map<String, ClassPath> mMapPermanentClassPath = new ConcurrentHashMap<>();

    public EasyTransform(Project project) {
        if (project == null) {
            throw new IllegalArgumentException(getClass().getName() + " 的构造函数参数 project 不能为 null");
        }

        mProject = project;
        BaseExtension extension = getExtension(mProject);
        if (extension instanceof AppExtension) {
            mCurrentScope = CurrentScope.PROJECT;
        } else if (extension instanceof LibraryExtension) {
            mCurrentScope = CurrentScope.SUB_PROJECT;
        } else {
            mCurrentScope = CurrentScope.UNKNOWN;
        }
    }

    /**
     * 判定所传入jar文件是否要进行transform操作
     *
     * @param jarFile 要进行判定的jar文件
     * @return true表示入参所传jar文件需要进行transform
     */
    protected abstract boolean isJarFileNeedModify(File jarFile);

    /**
     * 按实际需要修改传入都文件对象（注意：如需修改，只做修改即可，不必回写。后续处理步骤会进行回写）
     *
     * @param ctClass 文件对象
     * @return true：对所传入的文件对象进行了修改，false：未做修改
     */
    protected abstract boolean justModifyNotWriteBack(CtClass ctClass);

    protected abstract Set<? super QualifiedContent.Scope> getRawScopes();

    /**
     * 判断所传入文件是否要进行transform操作
     *
     * @param file 要进行判定的文件
     * @return true表示入参所传文件需要进行transform
     */
    protected boolean isValidInjectFile(File file) {
        if (file.isFile()) {
            String absolutePath = file.getAbsolutePath();
            return absolutePath.endsWith(".class") && !absolutePath.endsWith("R.class")
                    && !absolutePath.endsWith("BuildConfig.class") && !absolutePath.contains("R$");
        } else {
            return false;
        }
    }

    /**
     * 并行transform开关
     *
     * @return true：并行
     */
    protected boolean isParallel() {
        return true;
    }

    @Override
    public boolean isIncremental() {
        return true;
    }

    @Override
    final public Set<? super QualifiedContent.Scope> getScopes() {
        Set<? super QualifiedContent.Scope> set = getRawScopes();
        if (mCurrentScope == CurrentScope.SUB_PROJECT) {
            if ((set == null) || set.isEmpty()) {
                return set;
            } else {
                // 子模块只支持这一种类型，若有其他类型编译时会报错
                return ImmutableSet.of(QualifiedContent.Scope.PROJECT);
            }
        } else {
            return set;
        }
    }

    @Override
    public void transform(TransformInvocation transformInvocation) throws IOException {
        TransformOutputProvider outputProvider = transformInvocation.getOutputProvider();
        boolean isIncremental = transformInvocation.isIncremental();
        if (!isIncremental) {
            outputProvider.deleteAll();
        }
        Collection<TransformInput> inputs = transformInvocation.getInputs();
        if ((inputs == null) || inputs.isEmpty()) {
            return;
        }

        // 添加 android.jar 路径
        BaseExtension be = getExtension(mProject);
        if (be != null) {
            List<File> list = be.getBootClasspath();
            if (!list.isEmpty()) {
                String bootClasspath = list.get(0).getAbsolutePath();
                if (!TextUtils.isEmpty(bootClasspath)) {
                    try {
                        appendPermanentClassPath(bootClasspath);
                    } catch (NotFoundException e) {

                    }
                }
            }
        }

        WaitableExecutor executor = (isParallel() ? WaitableExecutor.useGlobalSharedThreadPool() : null);
        for (TransformInput ti : inputs) {
            transformJars(outputProvider, ti.getJarInputs(), isIncremental, executor);
            transformClasses(outputProvider, ti.getDirectoryInputs(), isIncremental, executor);
        }
        if (executor != null) {
            try {
                executor.waitForTasksWithQuickFail(true);
            } catch (InterruptedException e) {

            }
        }
    }

    private void transformJars(TransformOutputProvider outputProvider, Collection<JarInput> jarInputs,
                               boolean isIncremental, WaitableExecutor executor) {
        if (jarInputs == null) {
            return;
        }
        for (JarInput ji : jarInputs) {
            if (isIncremental) {
                Status status = ji.getStatus();
                switch (status) {
                    case ADDED:
                    case CHANGED:
                        doTransformJar(outputProvider, ji, executor);
                        break;
                    case REMOVED:
                        deleteJar(outputProvider, ji, executor);
                        break;
                    default:
                        break;
                }
            } else {
                doTransformJar(outputProvider, ji, executor);
            }
        }
    }

    private void doTransformJar(TransformOutputProvider outputProvider, JarInput ji, WaitableExecutor executor) {
        if (executor == null) {
            realDoTransformJar(outputProvider, ji, executor);
        } else {
            executor.execute(() -> {
                realDoTransformJar(outputProvider, ji, executor);
                return null;
            });
        }
    }

    private void realDoTransformJar(TransformOutputProvider outputProvider, JarInput ji, WaitableExecutor executor) {
        InjectJarFile ijf = injectJar(ji, executor);
        copyJar(outputProvider, ji, ijf);
    }

    private void deleteJar(TransformOutputProvider outputProvider, JarInput ji, WaitableExecutor executor) {
        if (executor == null) {
            deleteJar(outputProvider, ji);
        } else {
            executor.execute(() -> {
                deleteJar(outputProvider, ji);
                return null;
            });
        }
    }

    private void deleteJar(TransformOutputProvider outputProvider, JarInput ji) {
        File destJarFile = getDestJar(outputProvider, ji);
        try {
            FileUtils.deleteIfExists(destJarFile);
        } catch (IOException e) {

        }
    }

    private void copyJar(TransformOutputProvider outputProvider, JarInput ji, InjectJarFile ijf) {
        try {
            FileUtils.copyFile(ijf.mJarFile, getDestJar(outputProvider, ji));
            if (ijf.mHasModified) {
                FileUtils.deleteIfExists(ijf.mJarFile);
            }
        } catch (IOException e) {

        }
    }

    private File getDestJar(TransformOutputProvider outputProvider, JarInput ji) {
        return outputProvider.getContentLocation(ji.getFile().getAbsolutePath(),
                ji.getContentTypes(), ji.getScopes(), Format.JAR);
    }

    private void transformClasses(TransformOutputProvider outputProvider,
                                  Collection<DirectoryInput> directoryInputs, boolean isIncremental,
                                  WaitableExecutor executor) {
        if (directoryInputs == null) {
            return;
        }
        for (DirectoryInput diExcludePackage : directoryInputs) {
            if (isIncremental) {
                incrementalTransformClass(outputProvider, diExcludePackage, executor);
            } else {
                fullTransformClass(outputProvider, diExcludePackage, executor);
            }
        }
    }

    private void incrementalTransformClass(TransformOutputProvider outputProvider,
                                           DirectoryInput diExcludePackage, WaitableExecutor executor) {
        // diExcludePackage.getFile()：不包含包名的文件夹，类似.../app/build/tmp/kotlin-classes/debug
        Map<File, Status> fileStatusMap = diExcludePackage.getChangedFiles();
        if (fileStatusMap == null) {
            return;
        }
        fileStatusMap.forEach((classFile, status) -> {
            // classFile：class文件，如.../app/build/tmp/kotlin-classes/debug/com/lfa/mapleleafdemo/AnotherActivity.class
            switch (status) {
                case ADDED:
                case CHANGED:
                    transformModifySingleClass(outputProvider, diExcludePackage, classFile, executor);
                    break;
                case REMOVED:
                    transformDeleteSingleClass(outputProvider, diExcludePackage, classFile, executor);
                    break;
                default:
                    break;
            }
        });
    }

    // diExcludePackage.getFile()：不包含包名的文件夹，类似.../app/build/tmp/kotlin-classes/debug
    private void fullTransformClass(TransformOutputProvider outputProvider,
                                    DirectoryInput diExcludePackage, WaitableExecutor executor) {
        CountDownLatch countDownLatch = injectClasses(diExcludePackage.getFile().getAbsolutePath(), executor);
        if (countDownLatch != null) {
            try {
                // 因后续要拷贝整个class文件目录到新路径，所以这里需要等待原目录所有文件都处理完毕
                countDownLatch.await();
            } catch (InterruptedException e) {

            }
        }
        if (executor == null) {
            copyClasses(outputProvider, diExcludePackage);
        } else {
            executor.execute(() -> {
                copyClasses(outputProvider, diExcludePackage);
                return null;
            });
        }
    }

    // pathExcludePackage：不包含包名的文件夹，类似.../app/build/tmp/kotlin-classes/debug
    private CountDownLatch injectClasses(String pathExcludePackage, WaitableExecutor executor) {
        List<File> filesToInject = getInjectFiles(pathExcludePackage);
        int size = filesToInject.size();
        if (size < 1) {
            return null;
        }

        CountDownLatch countDownLatch = ((executor == null) ? null : new CountDownLatch(size));
        for (File file : filesToInject) {
            if (countDownLatch == null) {
                injectSingleClass(file, pathExcludePackage);
            } else {
                executor.execute(() -> {
                    try {
                        injectSingleClass(file, pathExcludePackage);
                    } finally {
                        countDownLatch.countDown();
                    }
                    return null;
                });
            }
        }
        return countDownLatch;
    }

    private List<File> getInjectFiles(String pathExcludePackage) {
        List<File> fs = null;
        Path path = Paths.get(pathExcludePackage);
        try {
            fs = Files.walk(path)
                    .map(Path::toFile)
                    .filter(this::isValidInjectFile)
                    .collect(Collectors.toList());
        } catch (IOException e) {

        }
        return (fs == null) ? Collections.emptyList() : fs;
    }

    // diExcludePackage.getFile()：不包含包名的文件夹，类似.../app/build/tmp/kotlin-classes/debug
    // classFile：class文件，如.../app/build/tmp/kotlin-classes/debug/com/lfa/mapleleafdemo/AnotherActivity.class
    private void transformModifySingleClass(TransformOutputProvider outputProvider,
                                            DirectoryInput diExcludePackage, File classFile,
                                            WaitableExecutor executor) {
        if (executor == null) {
            transformModifySingleClass(outputProvider, diExcludePackage, classFile);
        } else {
            executor.execute(() -> {
                transformModifySingleClass(outputProvider, diExcludePackage, classFile);
                return null;
            });
        }
    }

    private void transformModifySingleClass(TransformOutputProvider outputProvider,
                                            DirectoryInput diExcludePackage, File classFile) {
        injectSingleClass(classFile, diExcludePackage.getFile().getAbsolutePath());
        copySingleClass(outputProvider, diExcludePackage, classFile);
    }

    // diExcludePackage.getFile()：不包含包名的文件夹，类似.../app/build/tmp/kotlin-classes/debug
    // classFile：class文件，如.../app/build/tmp/kotlin-classes/debug/com/lfa/mapleleafdemo/AnotherActivity.class
    private void transformDeleteSingleClass(TransformOutputProvider outputProvider,
                                            DirectoryInput diExcludePackage, File classFile,
                                            WaitableExecutor executor) {
        if (executor == null) {
            transformDeleteSingleClass(outputProvider, diExcludePackage, classFile);
        } else {
            executor.execute(() -> {
                transformDeleteSingleClass(outputProvider, diExcludePackage, classFile);
                return null;
            });
        }
    }

    private void transformDeleteSingleClass(TransformOutputProvider outputProvider,
                                            DirectoryInput diExcludePackage, File classFile) {
        File destClassFile = getClassFileDestPath(outputProvider, diExcludePackage, classFile);
        try {
            FileUtils.deleteIfExists(destClassFile);
        } catch (IOException e) {

        }
    }

    // classFile：class文件，如.../app/build/tmp/kotlin-classes/debug/com/lfa/mapleleafdemo/AnotherActivity.class
    // pathExcludePackage：不包含包名的文件夹，类似.../app/build/tmp/kotlin-classes/debug
    private boolean injectSingleClass(File classFile, String pathExcludePackage) {
        ClassInfo cInfo = null;
        InputStream is = null;
        try {
            cInfo = getClassInfo(pathExcludePackage);
            is = new FileInputStream(classFile.getAbsoluteFile());
            CtClass c = cInfo.mClassPool.makeClass(is);
            return modifyClass(c, pathExcludePackage);
        } catch (IOException e) {

        } catch (NotFoundException e) {

        } catch (CannotCompileException e) {

        } finally {
            if (cInfo != null) {
                cInfo.mClassPool.removeClassPath(cInfo.mClassPath);
            }

            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {

                }
            }
        }
        return false;
    }

    private void copySingleClass(TransformOutputProvider outputProvider, DirectoryInput di, File from) {
        File to = getClassFileDestPath(outputProvider, di, from);
        try {
            FileUtils.copyFile(from, to);
        } catch (IOException e) {

        }
    }

    private File getClassFileDestPath(TransformOutputProvider outputProvider,
                                      DirectoryInput diExcludePackage, File fromClassFile) {
        // 类似 .../app/build/tmp/kotlin-classes/debug/com/lfa/mapleleafdemo/AnotherActivity.class
        String fromClassFilePath = fromClassFile.getAbsolutePath();

        // 类似 .../app/build/tmp/kotlin-classes/debug
        String fromDirExcludePackagePath = diExcludePackage.getFile().getAbsolutePath();

        // 类似 .../app/build/intermediates/transforms/MapleleafTransform/debug/0
        String toDirExcludePackagePath = getDestDirExcludePackage(outputProvider, diExcludePackage).getAbsolutePath();

        // 类似 .../app/build/intermediates/transforms/MapleleafTransform/debug/0/com/lfa/mapleleafdemo/AnotherActivity.class
        return new File(fromClassFilePath.replace(fromDirExcludePackagePath, toDirExcludePackagePath));
    }

    private InjectJarFile injectJar(JarInput ji, WaitableExecutor executor) {
        InjectJarFile ijf = new InjectJarFile();

        File jarFile = ji.getFile();
        if (!isJarFileNeedModify(jarFile)) {
            ijf.mJarFile = jarFile;
            ijf.mHasModified = false;
            return ijf;
        }

        File jarParentDir = jarFile.getParentFile();
        File tmpDir = new File(jarParentDir, UUID.randomUUID().toString());
        ZipUtil.unpack(jarFile, tmpDir);

        CountDownLatch countDownLatch = injectClasses(tmpDir.getAbsolutePath(), executor);
        if (countDownLatch != null) {
            try {
                countDownLatch.await();
            } catch (InterruptedException e) {

            }
        }

        File modifiedJarFile = new File(jarParentDir, UUID.randomUUID().toString());
        ZipUtil.pack(tmpDir, modifiedJarFile);
        try {
            FileUtils.deleteRecursivelyIfExists(tmpDir);
        } catch (IOException e) {

        }
        ijf.mJarFile = modifiedJarFile;
        ijf.mHasModified = true;
        return ijf;
    }

    private void copyClasses(TransformOutputProvider outputProvider, DirectoryInput diExcludePackage) {
        File toDirExcludePackagePath = getDestDirExcludePackage(outputProvider, diExcludePackage);
        try {
            FileUtils.copyDirectory(diExcludePackage.getFile(), toDirExcludePackagePath);
        } catch (IOException e) {

        }
    }

    // 返回值类似 .../app/build/intermediates/transforms/MapleleafTransform/debug/0
    private File getDestDirExcludePackage(TransformOutputProvider outputProvider, DirectoryInput diExcludePackage) {
        return outputProvider.getContentLocation(diExcludePackage.getName(),
                diExcludePackage.getContentTypes(), diExcludePackage.getScopes(), Format.DIRECTORY);
    }

    private ClassPool getClassPool() {
        return mClassPool;
    }

    private ClassInfo getClassInfo(String path) throws NotFoundException {
        ClassPool classPool = getClassPool();
        ClassPath classPath = classPool.appendClassPath(path);
        return new ClassInfo(classPool, classPath);
    }

    protected ClassPath appendPermanentClassPath(String pathname) throws NotFoundException {
        if (mMapPermanentClassPath.containsKey(pathname)) {
            return mMapPermanentClassPath.get(pathname);
        }
        ClassPool classPool = getClassPool();
        ClassPath cp = classPool.appendClassPath(pathname);
        mMapPermanentClassPath.put(pathname, cp);
        return cp;
    }

    // pathExcludePackage：不包含包名的文件夹，类似.../app/build/tmp/kotlin-classes/debug
    private boolean modifyClass(CtClass ctClass, String pathExcludePackage)
            throws CannotCompileException, IOException {
        boolean hasModified = justModifyNotWriteBack(ctClass);
        if (hasModified) {
            if (ctClass.isFrozen()) {
                ctClass.defrost();
            }
            ctClass.writeFile(pathExcludePackage);
            ctClass.detach();
        }
        return hasModified;
    }

    private static class ClassInfo {

        private ClassPool mClassPool;
        private ClassPath mClassPath;

        private ClassInfo(ClassPool classPool, ClassPath classPath) {
            mClassPool = classPool;
            mClassPath = classPath;
        }

    }

    private static class InjectJarFile {

        private File mJarFile;
        private boolean mHasModified;

    }

    public static BaseExtension getExtension(Project project) {
        if (project == null) {
            return null;
        }
        ExtensionContainer ec = project.getExtensions();
        AppExtension app = ec.findByType(AppExtension.class);
        return ((app == null) ? ec.findByType(LibraryExtension.class) : app);
    }

}
