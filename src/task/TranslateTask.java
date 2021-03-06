/*
 * Copyright 2018 Airsaid. https://github.com/airsaid
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import config.PluginConfig;
import constant.Constants;
import logic.ParseStringXml;
import module.AndroidString;
import module.Content;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import translate.lang.LANG;
import translate.querier.Querier;
import translate.trans.AbstractTranslator;
import translate.trans.impl.GoogleTranslator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * @author airsaid
 */
public class TranslateTask extends Task.Backgroundable {

    private List<LANG> mLanguages;
    private List<AndroidString> mAndroidStrings;
    private VirtualFile mSelectFile;
    private Map<String, List<AndroidString>> mWriteData;
    private OnTranslateListener mOnTranslateListener;

    public interface OnTranslateListener {
        void onTranslateSuccess();

        void onTranslateError(Throwable e);
    }

    public TranslateTask(@Nullable Project project, @Nls @NotNull String title, List<LANG> languages,
                         List<AndroidString> androidStrings, VirtualFile selectFile) {
        super(project, title);
        this.mLanguages = languages;
        this.mAndroidStrings = androidStrings;
        this.mSelectFile = selectFile;
        this.mWriteData = new HashMap<>();
    }

    boolean readCompleted = false;
    List<AndroidString> skipOrSelectList;

    @Override
    public void run(@NotNull ProgressIndicator progressIndicator) {
        boolean isOverwriteExistingString = PropertiesComponent.getInstance(myProject)
                .getBoolean(Constants.KEY_IS_OVERWRITE_EXISTING_STRING);
        Querier<AbstractTranslator> translator = new Querier<>();
        GoogleTranslator googleTranslator = new GoogleTranslator();
        translator.attach(googleTranslator);
        mWriteData.clear();

        for (LANG toLanguage : mLanguages) {
            if (progressIndicator.isCanceled()) break;

            progressIndicator.setText("Translating in the " + toLanguage.getEnglishName() + " language...");

            if (isOverwriteExistingString) {
                translate(progressIndicator, translator, toLanguage, null);
                continue;
            }
            readCompleted = false;
            ApplicationManager.getApplication().runReadAction(() -> {
                VirtualFile virtualFile = getVirtualFile(toLanguage);

                if (virtualFile == null) {
                    readCompleted = true;
                    return;
                }

                PsiFile psiFile = PsiManager.getInstance(myProject).findFile(virtualFile);
                if (psiFile == null) {
                    readCompleted = true;
                    return;
                }

                skipOrSelectList = ParseStringXml.parse(progressIndicator, psiFile, false);
                readCompleted = true;
            });
            while (!readCompleted) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    //ignore
                }
            }
            progressIndicator.setText("Translating in the " + toLanguage.getEnglishName() + " language...");
            translate(progressIndicator, translator, toLanguage, skipOrSelectList);
            skipOrSelectList = null;

        }
        googleTranslator.close();
        writeResultData(progressIndicator);
    }

    private int realTranslateCount = 0;

    private void translate(@NotNull ProgressIndicator progressIndicator, Querier<AbstractTranslator> translator, LANG toLanguage, @Nullable List<AndroidString> list) {
        List<AndroidString> writeAndroidString = new ArrayList<>();
        List<AndroidString> queryAndroidString = new ArrayList<>();
        StringBuilder queryTextBuilder = new StringBuilder();
        for (AndroidString androidString : mAndroidStrings) {
            if (progressIndicator.isCanceled()) break;

            if (!androidString.isTranslatable()) {
                continue;
            }

            // If the string to be translated already exists, use it directly
            if (list != null && list.contains(androidString)) {
                writeAndroidString.add(list.get(list.indexOf(androidString)));
                continue;
            }

            AndroidString clone = androidString.clone();
            List<Content> contexts = clone.getContents();
            for (Content content : contexts) {
                if (content.isIgnore()) continue; // Ignore text with xliff:g tags set

                if (PluginConfig.isTranslateTogether()) {
                    queryTextBuilder.append(content.getText());
                    queryTextBuilder.append("\n");
                } else {
                    translator.setParams(LANG.Auto, toLanguage, content.getText());
                    String result = translator.executeSingle();
                    if (result == null || result.trim().isEmpty() || result.equals(content.getText())) {
                        result = translator.executeSingle();
                        realTranslateCount++;
                    }
                    content.setText(result);
                    realTranslateCount++;
                }
            }
            queryAndroidString.add(clone);
            writeAndroidString.add(clone);
            if (queryTextBuilder.length() > 360||queryAndroidString.size()>28) {
                translator.setParams(LANG.Auto, toLanguage, queryTextBuilder.toString());
                String result = translator.executeSingle();
                ObjectMapper mapper = new ObjectMapper();
                try {
                    JsonNode jsonNode = mapper.readTree(result).get(5);
                    if (jsonNode.size() > 0) {
                        int i = 0;
                        for (AndroidString query : queryAndroidString) {
                            List<Content> tempContexts = query.getContents();
                            for (Content content : tempContexts) {
                                if (content.isIgnore()) continue; // Ignore text with xliff:g tags set
                                if (i < jsonNode.size()) {
                                    StringBuilder contentResult = new StringBuilder();
                                    for (; i < jsonNode.size(); i++) {
                                        if ("\n".equals(jsonNode.get(i).get(0).textValue())) {
                                            i++;
                                            break;
                                        }
                                        if (jsonNode.get(i).get(0).textValue() != null && jsonNode.get(i).get(0).textValue().contains("\n") && jsonNode.get(i).get(0).textValue().trim().isEmpty()) {
                                            contentResult.append(jsonNode.get(i).get(0).textValue().replace("\n", ""));
                                            i++;
                                            break;
                                        }
                                        contentResult.append(jsonNode.get(i).get(2).get(0).get(0).textValue());
                                    }
                                    content.setText(contentResult.toString());
                                } else {
                                    break;
                                }
                            }
                        }
                    }
                    queryAndroidString.clear();
                    queryTextBuilder = new StringBuilder();
                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                }
                realTranslateCount++;
            }
        }

        if (PluginConfig.isTranslateTogether()) {
            translator.setParams(LANG.Auto, toLanguage, queryTextBuilder.toString());
            String result = translator.executeSingle();
            ObjectMapper mapper = new ObjectMapper();
            try {
                JsonNode jsonNode = mapper.readTree(result).get(5);
                if (jsonNode.size() > 0) {
                    int i = 0;
                    for (AndroidString query : queryAndroidString) {
                        List<Content> contexts = query.getContents();
                        for (Content content : contexts) {
                            if (content.isIgnore()) continue; // Ignore text with xliff:g tags set
                            if (i < jsonNode.size()) {
                                StringBuilder contentResult = new StringBuilder();
                                for (; i < jsonNode.size(); i++) {
                                    if ("\n".equals(jsonNode.get(i).get(0).textValue())) {
                                        i++;
                                        break;
                                    }
                                    if (jsonNode.get(i).get(0).textValue() != null && jsonNode.get(i).get(0).textValue().contains("\n") && jsonNode.get(i).get(0).textValue().trim().isEmpty()) {
                                        contentResult.append(jsonNode.get(i).get(0).textValue().replace("\n", ""));
                                        i++;
                                        break;
                                    }
                                    contentResult.append(jsonNode.get(i).get(2).get(0).get(0).textValue());
                                }
                                content.setText(contentResult.toString());
                            } else {
                                break;
                            }
                        }
                    }
                }
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
            realTranslateCount++;
        }

        mWriteData.put(toLanguage.getCode(), writeAndroidString);
        if (realTranslateCount > 300 || (realTranslateCount + mAndroidStrings.size()) > 300) {
            realTranslateCount = 0;
            progressIndicator.setText("Translating too many times sleep 10s ");
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void writeResultData(ProgressIndicator progressIndicator) {
        if (progressIndicator.isCanceled()) return;

        if (mWriteData == null) {
            translateError(new IllegalArgumentException("No translate data."));
            return;
        }

        Set<String> keySet = mWriteData.keySet();
        for (String key : keySet) {
            if (progressIndicator.isCanceled()) break;

            File writeFile = getWriteFileForCode(key);
            progressIndicator.setText("Write to " + writeFile.getParentFile().getName() + " data...");
            write(writeFile, mWriteData.get(key));
            refreshAndOpenFile(writeFile);
        }
    }

    private VirtualFile getVirtualFile(LANG lang) {
        File file = getStringFile(lang.getCode());
        return LocalFileSystem.getInstance().findFileByIoFile(file);
    }

    private File getStringFile(String langCode) {
        return getStringFile(langCode, false);
    }

    private File getStringFile(String langCode, boolean mkdirs) {
        String parentPath = mSelectFile.getParent().getParent().getPath();
        File stringFile;
        if (mkdirs) {
            File parentFile = new File(parentPath, getDirNameForCode(langCode));
            if (!parentFile.exists()) {
                parentFile.mkdirs();
            }
            stringFile = new File(parentFile, "strings.xml");
            if (!stringFile.exists()) {
                try {
                    stringFile.createNewFile();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } else {
            stringFile = new File(parentPath.concat(File.separator).concat(getDirNameForCode(langCode)), "strings.xml");
        }
        return stringFile;
    }

    private File getWriteFileForCode(String langCode) {
        return getStringFile(langCode, true);
    }

    private String getDirNameForCode(String langCode) {
        String suffix;
        if (langCode.equals(LANG.ChineseSimplified.getCode())) {
            suffix = "zh-rCN";
        } else if (langCode.equals(LANG.ChineseTraditional.getCode())) {
            suffix = "zh-rTW";
        } else if (langCode.equals(LANG.Filipino.getCode())) {
            suffix = "fil";
        } else if (langCode.equals(LANG.Indonesian.getCode())) {
            suffix = "in-rID";
        } else if (langCode.equals(LANG.Javanese.getCode())) {
            suffix = "jv";
        } else {
            suffix = langCode;
        }
        return "values-".concat(suffix);
    }

    private void write(File file, List<AndroidString> androidStrings) {
        ApplicationManager.getApplication().invokeLater(() -> ApplicationManager.getApplication().runWriteAction(() -> {
            try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8))) {
                bw.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
                bw.newLine();
                bw.write("<resources");
                Map<String, String> attrs = AndroidString.getOriginalAttrs();
                if (attrs != null && attrs.size() > 0) {
                    bw.write(" ");
                    int i = 1;
                    for (String key : attrs.keySet()) {
                        if (i == attrs.size()) {
                            bw.write(key + "=\"" + attrs.get(key) + "\"");
                        } else {
                            bw.write(key + "=\"" + attrs.get(key) + "\" ");
                        }
                        i++;
                    }
                }
                bw.write(">");
                bw.newLine();
                for (AndroidString androidString : androidStrings) {
                    bw.write("\t<string");
                    attrs = androidString.getAttrs();
                    if (attrs != null && attrs.size() > 0) {
                        bw.write(" ");
                        int i = 1;
                        for (String key : attrs.keySet()) {
                            if (i == attrs.size()) {
                                bw.write(key + "=\"" + attrs.get(key) + "\"");
                            } else {
                                bw.write(key + "=\"" + attrs.get(key) + "\" ");
                            }
                            i++;
                        }
                    }
                    bw.write(">");
                    for (Content content : androidString.getContents()) {
                        if (content.getTagName() != null && !content.getTagName().trim().isEmpty()) {
                            bw.write("<" + content.getTagName());
                            attrs = content.getAttrs();
                            if (attrs != null && attrs.size() > 0) {
                                bw.write(" ");
                                int i = 1;
                                for (String key : attrs.keySet()) {
                                    if (i == attrs.size()) {
                                        bw.write(key + "=\"" + attrs.get(key) + "\"");
                                    } else {
                                        bw.write(key + "=\"" + attrs.get(key) + "\" ");
                                    }
                                    i++;
                                }
                            }
                            bw.write(">");
                            bw.write(content.getText());
                            bw.write("</" + content.getTagName() + ">");
                        } else {
                            bw.write(content.getText());
                        }
                    }
                    bw.write("</string>");
                    bw.newLine();
                }
                bw.write("</resources>");
                bw.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }));
    }

    private void refreshAndOpenFile(File file) {
        VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
        if (virtualFile != null) {
            ApplicationManager.getApplication().invokeLater(() ->
                    FileEditorManager.getInstance(myProject).openFile(virtualFile, true));
        }
    }

    @Override
    public void onSuccess() {
        super.onSuccess();
        translateSuccess();
    }

    @Override
    public void onThrowable(@NotNull Throwable error) {
        super.onThrowable(error);
        translateError(error);
    }

    private void translateSuccess() {
        if (mOnTranslateListener != null) {
            mOnTranslateListener.onTranslateSuccess();
        }
    }

    private void translateError(Throwable error) {
        if (mOnTranslateListener != null) {
            mOnTranslateListener.onTranslateError(error);
        }
    }

    /**
     * Set translate result listener.
     *
     * @param listener callback interface. success or fail.
     */
    public void setOnTranslateListener(OnTranslateListener listener) {
        this.mOnTranslateListener = listener;
    }

}
