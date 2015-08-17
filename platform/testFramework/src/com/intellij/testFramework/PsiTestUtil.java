/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.testFramework;

import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkModificator;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.impl.ContentEntryImpl;
import com.intellij.openapi.roots.impl.libraries.ProjectLibraryTable;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.impl.DebugUtil;
import com.intellij.util.Consumer;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.ThrowableRunnable;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.JpsElement;
import org.jetbrains.jps.model.java.JavaSourceRootType;
import org.jetbrains.jps.model.module.JpsModuleSourceRootType;
import org.jetbrains.platform.loader.PlatformLoader;
import org.jetbrains.platform.loader.repository.RuntimeModuleId;
import org.junit.Assert;

import java.io.File;
import java.io.IOException;
import java.util.*;


@NonNls
public class PsiTestUtil {
  public static VirtualFile createTestProjectStructure(Project project,
                                                       Module module,
                                                       String rootPath,
                                                       Collection<File> filesToDelete) throws Exception {
    return createTestProjectStructure(project, module, rootPath, filesToDelete, true);
  }

  public static VirtualFile createTestProjectStructure(Project project, Module module, Collection<File> filesToDelete) throws IOException {
    return createTestProjectStructure(project, module, null, filesToDelete, true);
  }

  public static VirtualFile createTestProjectStructure(Project project,
                                                       Module module,
                                                       String rootPath,
                                                       Collection<File> filesToDelete,
                                                       boolean addProjectRoots) throws IOException {
    VirtualFile vDir = createTestProjectStructure(module, rootPath, filesToDelete, addProjectRoots);
    PsiDocumentManager.getInstance(project).commitAllDocuments();
    return vDir;
  }

  public static VirtualFile createTestProjectStructure(Module module,
                                                       String rootPath,
                                                       Collection<File> filesToDelete,
                                                       boolean addProjectRoots) throws IOException {
    return createTestProjectStructure("unitTest", module, rootPath, filesToDelete, addProjectRoots);
  }

  public static VirtualFile createTestProjectStructure(String tempName,
                                                       final Module module,
                                                       final String rootPath,
                                                       final Collection<File> filesToDelete,
                                                       final boolean addProjectRoots) throws IOException {
    File dir = FileUtil.createTempDirectory(tempName, null, false);
    filesToDelete.add(dir);

    final VirtualFile vDir =
      LocalFileSystem.getInstance().refreshAndFindFileByPath(dir.getCanonicalPath().replace(File.separatorChar, '/'));
    assert vDir != null && vDir.isDirectory() : dir;
    PlatformTestCase.synchronizeTempDirVfs(vDir);

    EdtTestUtil.runInEdtAndWait(new ThrowableRunnable<Throwable>() {
      @Override
      public void run() throws Throwable {
        AccessToken token = WriteAction.start();
        try {
          if (rootPath != null) {
            VirtualFile vDir1 = LocalFileSystem.getInstance().findFileByPath(rootPath.replace(File.separatorChar, '/'));
            if (vDir1 == null) {
              throw new Exception(rootPath + " not found");
            }
            VfsUtil.copyDirectory(null, vDir1, vDir, null);
          }

          if (addProjectRoots) {
            addSourceContentToRoots(module, vDir);
          }
        }
        finally {
          token.finish();
        }
      }
    });
    return vDir;
  }

  public static void removeAllRoots(Module module, final Sdk jdk) {
    ModuleRootModificationUtil.updateModel(module, new Consumer<ModifiableRootModel>() {
      @Override
      public void consume(ModifiableRootModel model) {
        model.clear();
        model.setSdk(jdk);
      }
    });
  }

  public static void addSourceContentToRoots(Module module, @NotNull VirtualFile vDir) {
    addSourceContentToRoots(module, vDir, false);
  }

  public static void addSourceContentToRoots(Module module, @NotNull final VirtualFile vDir, final boolean testSource) {
    ModuleRootModificationUtil.updateModel(module, new Consumer<ModifiableRootModel>() {
      @Override
      public void consume(ModifiableRootModel model) {
        model.addContentEntry(vDir).addSourceFolder(vDir, testSource);
      }
    });
  }

  public static void addSourceRoot(Module module, final VirtualFile vDir) {
    addSourceRoot(module, vDir, false);
  }

  public static void addSourceRoot(final Module module, final VirtualFile vDir, final boolean isTestSource) {
    addSourceRoot(module, vDir, isTestSource ? JavaSourceRootType.TEST_SOURCE : JavaSourceRootType.SOURCE);
  }

  public static <P extends JpsElement> void addSourceRoot(Module module,
                                                          final VirtualFile vDir,
                                                          @NotNull final JpsModuleSourceRootType<P> rootType) {
    addSourceRoot(module, vDir, rootType, rootType.createDefaultProperties());
  }

  public static <P extends JpsElement> void addSourceRoot(Module module, final VirtualFile vDir,
                                                          @NotNull final JpsModuleSourceRootType<P> rootType, final P properties) {
    ModuleRootModificationUtil.updateModel(module, new Consumer<ModifiableRootModel>() {
      @SuppressWarnings("unchecked")
      @Override
      public void consume(ModifiableRootModel model) {
        ContentEntry entry = findContentEntry(model, vDir);
        if (entry == null) entry = model.addContentEntry(vDir);
        entry.addSourceFolder(vDir, rootType, properties);
      }
    });
  }

  @Nullable
  private static ContentEntry findContentEntry(ModuleRootModel rootModel, final VirtualFile file) {
    return ContainerUtil.find(rootModel.getContentEntries(), new Condition<ContentEntry>() {
      @Override
      public boolean value(final ContentEntry object) {
        VirtualFile entryRoot = object.getFile();
        return entryRoot != null && VfsUtilCore.isAncestor(entryRoot, file, false);
      }
    });
  }

  public static ContentEntry addContentRoot(Module module, final VirtualFile vDir) {
    ModuleRootModificationUtil.updateModel(module, new Consumer<ModifiableRootModel>() {
      @Override
      public void consume(ModifiableRootModel model) {
        model.addContentEntry(vDir);
      }
    });

    for (ContentEntry entry : ModuleRootManager.getInstance(module).getContentEntries()) {
      if (Comparing.equal(entry.getFile(), vDir)) {
        Assert.assertFalse(((ContentEntryImpl)entry).isDisposed());
        return entry;
      }
    }

    return null;
  }

  public static void addExcludedRoot(Module module, final VirtualFile dir) {
    ModuleRootModificationUtil.updateModel(module, new Consumer<ModifiableRootModel>() {
      @Override
      public void consume(final ModifiableRootModel model) {
        ApplicationManager.getApplication().runReadAction(new Runnable() {
          @Override
          public void run() {
            findContentEntryWithAssertion(model, dir).addExcludeFolder(dir);
          }
        });
      }
    });
  }

  @NotNull
  private static ContentEntry findContentEntryWithAssertion(ModifiableRootModel model, VirtualFile dir) {
    ContentEntry entry = findContentEntry(model, dir);
    if (entry == null) {
      throw new RuntimeException(dir + " is not under content roots: " + Arrays.toString(model.getContentRoots()));
    }
    return entry;
  }

  public static void removeContentEntry(Module module, final VirtualFile contentRoot) {
    ModuleRootModificationUtil.updateModel(module, new Consumer<ModifiableRootModel>() {
      @Override
      public void consume(ModifiableRootModel model) {
        model.removeContentEntry(findContentEntryWithAssertion(model, contentRoot));
      }
    });
  }

  public static void removeSourceRoot(Module module, final VirtualFile root) {
    ModuleRootModificationUtil.updateModel(module, new Consumer<ModifiableRootModel>() {
      @Override
      public void consume(ModifiableRootModel model) {
        ContentEntry entry = findContentEntryWithAssertion(model, root);
        for (SourceFolder sourceFolder : entry.getSourceFolders()) {
          if (root.equals(sourceFolder.getFile())) {
            entry.removeSourceFolder(sourceFolder);
            break;
          }
        }
      }
    });
  }

  public static void removeExcludedRoot(Module module, final VirtualFile root) {
    ModuleRootModificationUtil.updateModel(module, new Consumer<ModifiableRootModel>() {
      @Override
      public void consume(ModifiableRootModel model) {
        ContentEntry entry = findContentEntryWithAssertion(model, root);
        entry.removeExcludeFolder(root.getUrl());
      }
    });
  }

  public static void checkFileStructure(PsiFile file) throws IncorrectOperationException {
    String originalTree = DebugUtil.psiTreeToString(file, false);
    PsiFile dummyFile = PsiFileFactory.getInstance(file.getProject()).createFileFromText(file.getName(), file.getFileType(), file.getText());
    String reparsedTree = DebugUtil.psiTreeToString(dummyFile, false);
    Assert.assertEquals(reparsedTree, originalTree);
  }

  public static void addLibrary(final Module module, final String libPath) {
    File file = new File(libPath);
    String libName = file.getName();
    addLibrary(module, libName, file.getParent(), libName);
  }

  public static void addLibrary(final Module module, final String libName, final String libPath, final String... jarArr) {
    ModuleRootModificationUtil.updateModel(module, new Consumer<ModifiableRootModel>() {
      @Override
      public void consume(ModifiableRootModel model) {
        addLibrary(module, model, libName, libPath, jarArr);
      }
    });
  }

  public static void addProjectLibrary(final Module module, final String libName, final VirtualFile... classesRoots) {
    addProjectLibrary(module, libName, Arrays.asList(classesRoots), Collections.<VirtualFile>emptyList());
  }

  public static Library addProjectLibrary(final Module module, final String libName, final List<VirtualFile> classesRoots,
                                       final List<VirtualFile> sourceRoots) {
    final Ref<Library> result = Ref.create();
    ModuleRootModificationUtil.updateModel(module, new Consumer<ModifiableRootModel>() {
      @Override
      public void consume(ModifiableRootModel model) {
        result.set(addProjectLibrary(module.getProject(), model, libName, classesRoots, sourceRoots));
      }
    });
    return result.get();
  }

  private static Library addProjectLibrary(final Project project, final ModifiableRootModel model,
                                           final String libName,
                                           final List<VirtualFile> classesRoots,
                                           final List<VirtualFile> sourceRoots) {
    final LibraryTable libraryTable = ProjectLibraryTable.getInstance(project);
    RunResult<Library> result = new WriteAction<Library>() {
      @Override
      protected void run(@NotNull Result<Library> result) throws Throwable {
        Library library = libraryTable.createLibrary(libName);
        Library.ModifiableModel libraryModel = library.getModifiableModel();
        try {
          for (VirtualFile root : classesRoots) {
            libraryModel.addRoot(root, OrderRootType.CLASSES);
          }
          for (VirtualFile root : sourceRoots) {
            libraryModel.addRoot(root, OrderRootType.SOURCES);
          }
          libraryModel.commit();
        }
        catch (Throwable t) {
          //noinspection SSBasedInspection
          libraryModel.dispose();
          throw t;
        }

        model.addLibraryEntry(library);
        OrderEntry[] orderEntries = model.getOrderEntries();
        OrderEntry last = orderEntries[orderEntries.length - 1];
        System.arraycopy(orderEntries, 0, orderEntries, 1, orderEntries.length - 1);
        orderEntries[0] = last;
        model.rearrangeOrderEntries(orderEntries);
        result.setResult(library);
      }
    }.execute();
    result.throwException();
    return result.getResultObject();
  }

  public static void addLibrary(final Module module,
                                final ModifiableRootModel model,
                                final String libName,
                                final String libPath,
                                final String... jarArr) {
    List<VirtualFile> classesRoots = new ArrayList<VirtualFile>();
    for (String jar : jarArr) {
      if (!libPath.endsWith("/") && !jar.startsWith("/")) {
        jar = "/" + jar;
      }
      final String path = libPath + jar;
      VirtualFile root;
      if (path.endsWith(".jar")) {
        root = JarFileSystem.getInstance().refreshAndFindFileByPath(path + "!/");
      }
      else {
        root = LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
      }
      assert root != null : "Library root folder not found: " + path + "!/";
      classesRoots.add(root);
    }
    addProjectLibrary(module.getProject(), model, libName, classesRoots, Collections.<VirtualFile>emptyList());
  }

  public static void addLibrary(final Module module,
                                final ModifiableRootModel model,
                                String libName,
                                RuntimeModuleId id) {
    List<VirtualFile> classesRoots = new ArrayList<VirtualFile>();
    for (String path : PlatformLoader.getInstance().getRepository().getModuleRootPaths(id)) {
      String url = VfsUtil.getUrlForLibraryRoot(new File(path));
      VirtualFile root = VirtualFileManager.getInstance().refreshAndFindFileByUrl(url);
      assert root != null : "Library root folder not found: " + url;
      classesRoots.add(root);
    }
    addProjectLibrary(module.getProject(), model, libName, classesRoots, Collections.<VirtualFile>emptyList());
  }

  public static void addLibrary(final Module module,
                                final String libName, final String libDir,
                                final String[] classRoots,
                                final String[] sourceRoots) {
    final String parentUrl =
      VirtualFileManager.constructUrl((classRoots.length > 0 ? classRoots[0]:sourceRoots[0]).endsWith(".jar!/") ? JarFileSystem.PROTOCOL : LocalFileSystem.PROTOCOL, libDir);
    List<String> classesUrls = new ArrayList<String>();
    List<String> sourceUrls = new ArrayList<String>();
    for (String classRoot : classRoots) {
      classesUrls.add(parentUrl + classRoot);
    }
    for (String sourceRoot : sourceRoots) {
      sourceUrls.add(parentUrl + sourceRoot);
    }
    ModuleRootModificationUtil.addModuleLibrary(module, libName, classesUrls, sourceUrls);
  }

  public static Module addModule(final Project project, final ModuleType type, final String name, final VirtualFile root) {
    return new WriteCommandAction<Module>(project) {
      @Override
      protected void run(@NotNull Result<Module> result) throws Throwable {
        String moduleName;
        ModifiableModuleModel moduleModel = ModuleManager.getInstance(project).getModifiableModel();
        try {
          moduleName = moduleModel.newModule(root.getPath() + "/" + name + ".iml", type.getId()).getName();
          moduleModel.commit();
        }
        catch (Throwable t) {
          moduleModel.dispose();
          throw t;
        }

        Module dep = ModuleManager.getInstance(project).findModuleByName(moduleName);
        assert dep != null : moduleName;

        ModifiableRootModel model = ModuleRootManager.getInstance(dep).getModifiableModel();
        try {
          model.addContentEntry(root).addSourceFolder(root, false);
          model.commit();
        }
        catch (Throwable t) {
          model.dispose();
          throw t;
        }

        result.setResult(dep);
      }
    }.execute().getResultObject();
  }

  public static void setCompilerOutputPath(Module module, final String url, final boolean forTests) {
    ModuleRootModificationUtil.updateModel(module, new Consumer<ModifiableRootModel>() {
      @Override
      public void consume(ModifiableRootModel model) {
        CompilerModuleExtension extension = model.getModuleExtension(CompilerModuleExtension.class);
        extension.inheritCompilerOutputPath(false);
        if (forTests) {
          extension.setCompilerOutputPathForTests(url);
        }
        else {
          extension.setCompilerOutputPath(url);
        }
      }
    });
  }

  public static void setExcludeCompileOutput(Module module, final boolean exclude) {
    ModuleRootModificationUtil.updateModel(module, new Consumer<ModifiableRootModel>() {
      @Override
      public void consume(ModifiableRootModel model) {
        model.getModuleExtension(CompilerModuleExtension.class).setExcludeOutput(exclude);
      }
    });
  }

  public static void setJavadocUrls(Module module, final String... urls) {
    ModuleRootModificationUtil.updateModel(module, new Consumer<ModifiableRootModel>() {
      @Override
      public void consume(ModifiableRootModel model) {
        model.getModuleExtension(JavaModuleExternalPaths.class).setJavadocUrls(urls);
      }
    });
  }

  public static Sdk addJdkAnnotations(Sdk sdk) {
    VirtualFile root = LocalFileSystem.getInstance().findFileByPath(
      FileUtil.toSystemIndependentName(PlatformTestUtil.getCommunityPath()) + "/java/jdkAnnotations");
    if (root != null) {
      SdkModificator sdkModificator = sdk.getSdkModificator();
      sdkModificator.addRoot(root, AnnotationOrderRootType.getInstance());
      sdkModificator.commitChanges();
    }
    return sdk;
  }
}