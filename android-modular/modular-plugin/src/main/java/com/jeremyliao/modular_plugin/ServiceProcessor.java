package com.jeremyliao.modular_plugin;

import com.android.SdkConstants;
import com.android.build.gradle.api.ApplicationVariant;
import com.android.builder.model.AndroidProject;
import com.android.utils.FileUtils;
import com.google.gson.Gson;
import com.jeremyliao.modular_base.inner.bean.Event;
import com.jeremyliao.modular_base.inner.bean.ModuleEventsInfo;
import com.jeremyliao.modular_base.inner.bean.ModuleInfo;
import com.jeremyliao.modular_base.inner.utils.GsonUtil;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;

import org.apache.commons.io.IOUtils;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.file.FileVisitor;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskOutputs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.lang.model.element.Modifier;

/**
 * Created by liaohailiang on 2018/8/30.
 */
public class ServiceProcessor {

    private static final String TAG = "-----------ServiceProcessor----------";
    private static final String MODULAR_PATH = "META-INF/modules/module_info/";
    private static final String ASSET_PATH = "modules/";
    private static final String ASSET_File = "module_info";

    private List<String> moduleInfos = new ArrayList<>();

    public void findServices(Project project, TaskOutputs outputs, ApplicationVariant variant) {
        if (!outputs.getHasOutput()) {
            System.out.println(TAG + "no output");
            return;
        }

        System.out.println(TAG + "findServices");
        long ms = System.currentTimeMillis();
        FileCollection files = outputs.getFiles();
        for (File file : files) {
            String path = file.getPath();
            FileTree tree = project.fileTree(new HashMap<String, Object>() {{
                System.out.println(TAG + "put dir: " + file);
                put("dir", file);
            }});
            System.out.println(TAG + "process file tree: " + file);

            processDirectories(path, tree);

            processJarFiles(tree);
        }

        // 处理Service文件
        String servicesFolderName = getClassesDir(project, variant, MODULAR_PATH);
        FileTree servicesTree = project.fileTree(new HashMap<String, Object>() {{
            put("dir", servicesFolderName);
        }});

        System.out.println(TAG + "process classes dir:" + servicesTree);

        servicesTree.visit(new FileVisitor() {
            @Override
            public void visitDir(FileVisitDetails fileVisitDetails) {

            }

            @Override
            public void visitFile(FileVisitDetails fileVisitDetails) {
                processFile(fileVisitDetails.getFile());
            }
        });
    }

    public void writeToAssets(Project project, ApplicationVariant variant) {
        if (moduleInfos.isEmpty()) {
            System.out.println(TAG + "writeToAssets kipped, no service found");
            return;
        }

        System.out.println(TAG + "writeToAssets start...");
        long ms = System.currentTimeMillis();
        File dir = new File(getAssetsDir(project, variant, ASSET_PATH));
        if (dir.isFile()) {
            dir.delete();
        }
        dir.mkdirs();
        try (PrintWriter writer = new PrintWriter(new File(dir, ASSET_File))) {
            for (String moduleInfo : moduleInfos) {
                System.out.println(TAG + "writeToAssets moduleInfo: " + moduleInfo);
                writer.println(moduleInfo);
            }
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println(TAG + "writeToAssets cost: " + (System.currentTimeMillis() - ms));
    }

    private static String getAssetsDir(Project project, ApplicationVariant variant, String path) {
        return FileUtils.join(project.getBuildDir().getPath(),
                AndroidProject.FD_INTERMEDIATES,
                "assets",
                variant.getDirName(),
                path);
    }

    private void processDirectories(String path, FileTree tree) {
        FileCollection filter = tree.filter(new Spec<File>() {
            @Override
            public boolean isSatisfiedBy(File file) {
                return file.getPath().substring(path.length()).contains(MODULAR_PATH);
            }
        });
        for (File f : filter) {
            processFile(f);
        }
    }

    private void processJarFiles(FileTree tree) {
        FileCollection filter = tree.filter(new Spec<File>() {
            @Override
            public boolean isSatisfiedBy(File file) {
                return file.getName().endsWith(SdkConstants.DOT_JAR);
            }
        });
        for (File jarFile : filter) {
            processJarFile(jarFile);
        }
    }

    private void processFile(File f) {
        System.out.println(TAG + "processFile: " + f);
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            processFileContent(reader, f.getName());
        } catch (IOException e) {
            e.printStackTrace();
        }
        f.delete();
    }

    private void processFileContent(BufferedReader reader, String moduleName)
            throws IOException {
        System.out.println(TAG + "processFileContent moduleName: " + moduleName);
        StringBuffer stringBuffer = new StringBuffer();
        String line;
        while ((line = reader.readLine()) != null) {
            stringBuffer.append(line);
        }
        moduleInfos.add(stringBuffer.toString());
    }

    private void processJarFile(File jarFile) {
        System.out.println(TAG + "processJarFile: " + jarFile);
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            if (hasServiceEntry(zipFile)) {
                System.out.println(TAG + "hasServiceEntry: " + zipFile);
                File tempFile = new File(jarFile.getPath() + ".tmp");
                try (ZipInputStream is = new ZipInputStream(new FileInputStream(jarFile));
                     ZipOutputStream os = new ZipOutputStream(new FileOutputStream(tempFile))) {
                    ZipEntry entry;
                    while ((entry = is.getNextEntry()) != null) {
                        if (isServiceEntry(entry)) {
                            try {
                                System.out.println(TAG + "entry name: " + entry.getName());
                                String moduleName = getSuffix(entry.getName(), "/");
                                System.out.println(TAG + "moduleName: " + moduleName);
                                BufferedReader reader = new BufferedReader(
                                        new InputStreamReader(is));
                                processFileContent(reader, moduleName);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        } else {
                            os.putNextEntry(new ZipEntry(entry));
                            IOUtils.copy(is, os);
                        }
                    }
                    jarFile.delete();
                    tempFile.renameTo(jarFile);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String getSuffix(String s, String splitter) {
        int i = s.lastIndexOf(splitter);
        if (i < 0) {
            return s;
        } else {
            return s.substring(i + splitter.length());
        }
    }

    private boolean hasServiceEntry(ZipFile zipFile) {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (isServiceEntry(entry)) {
                return true;
            }
        }
        return false;
    }

    private boolean isServiceEntry(ZipEntry entry) {
        return !entry.isDirectory() && entry.getName().startsWith(MODULAR_PATH);
    }

    private static String getClassesDir(Project project, ApplicationVariant variant, String path) {
        return FileUtils.join(project.getBuildDir().getPath(),
                AndroidProject.FD_INTERMEDIATES,
                "classes",
                variant.getDirName(),
                path);
    }

    private static String getAptDir(Project project, ApplicationVariant variant) {
        return FileUtils.join(project.getBuildDir().getPath(),
                AndroidProject.FD_GENERATED,
                "source",
                "apt",
                variant.getDirName());
    }
}
