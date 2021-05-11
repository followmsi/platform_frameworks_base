/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.graphics.fonts;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.FontListParser;
import android.graphics.fonts.FontManager;
import android.graphics.fonts.FontStyle;
import android.graphics.fonts.FontUpdateRequest;
import android.graphics.fonts.SystemFonts;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.Presubmit;
import android.system.Os;
import android.text.FontConfig;
import android.util.Xml;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class UpdatableFontDirTest {

    /**
     * A {@link UpdatableFontDir.FontFileParser} for testing. Instead of using real font files,
     * this test uses fake font files. A fake font file has its PostScript naem and revision as the
     * file content.
     */
    private static class FakeFontFileParser implements UpdatableFontDir.FontFileParser {
        @Override
        public String getPostScriptName(File file) throws IOException {
            String content = FileUtils.readTextFile(file, 100, "");
            return content.split(",")[2];
        }

        @Override
        public String buildFontFileName(File file) throws IOException {
            String content = FileUtils.readTextFile(file, 100, "");
            return content.split(",")[0];
        }

        @Override
        public long getRevision(File file) throws IOException {
            String content = FileUtils.readTextFile(file, 100, "");
            return Long.parseLong(content.split(",")[1]);
        }

        @Override
        public void tryToCreateTypeface(File file) throws IOException {
        }
    }

    // FakeFsverityUtil will successfully set up fake fs-verity if the signature is GOOD_SIGNATURE.
    private static final String GOOD_SIGNATURE = "Good signature";

    /** A fake FsverityUtil to keep fake verity bit in memory. */
    private static class FakeFsverityUtil implements UpdatableFontDir.FsverityUtil {
        private final Set<String> mHasFsverityPaths = new HashSet<>();

        public void remove(String name) {
            mHasFsverityPaths.remove(name);
        }

        @Override
        public boolean hasFsverity(String path) {
            return mHasFsverityPaths.contains(path);
        }

        @Override
        public void setUpFsverity(String path, byte[] pkcs7Signature) throws IOException {
            String fakeSignature = new String(pkcs7Signature, StandardCharsets.UTF_8);
            if (GOOD_SIGNATURE.equals(fakeSignature)) {
                mHasFsverityPaths.add(path);
            } else {
                throw new IOException("Failed to set up fake fs-verity");
            }
        }

        @Override
        public boolean rename(File src, File dest) {
            if (src.renameTo(dest)) {
                mHasFsverityPaths.remove(src.getAbsolutePath());
                mHasFsverityPaths.add(dest.getAbsolutePath());
                return true;
            }
            return false;
        }
    }

    private static final long CURRENT_TIME = 1234567890L;

    private File mCacheDir;
    private File mUpdatableFontFilesDir;
    private File mConfigFile;
    private List<File> mPreinstalledFontDirs;
    private Supplier<Long> mCurrentTimeSupplier = () -> CURRENT_TIME;
    private Function<Map<String, File>, FontConfig> mConfigSupplier =
            (map) -> SystemFonts.getSystemFontConfig(map, 0, 0);

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mCacheDir = new File(context.getCacheDir(), "UpdatableFontDirTest");
        FileUtils.deleteContentsAndDir(mCacheDir);
        mCacheDir.mkdirs();
        mUpdatableFontFilesDir = new File(mCacheDir, "updatable_fonts");
        mUpdatableFontFilesDir.mkdir();
        mPreinstalledFontDirs = new ArrayList<>();
        mPreinstalledFontDirs.add(new File(mCacheDir, "system_fonts"));
        mPreinstalledFontDirs.add(new File(mCacheDir, "product_fonts"));
        for (File dir : mPreinstalledFontDirs) {
            dir.mkdir();
        }
        mConfigFile = new File(mCacheDir, "config.xml");
    }

    @After
    public void tearDown() {
        FileUtils.deleteContentsAndDir(mCacheDir);
    }

    @Test
    public void construct() throws Exception {
        long expectedModifiedDate = CURRENT_TIME / 2;
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
        config.lastModifiedMillis = expectedModifiedDate;
        writeConfig(config, mConfigFile);
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dirForPreparation.loadFontFileMap();
        assertThat(dirForPreparation.getSystemFontConfig().getLastModifiedTimeMillis())
                .isEqualTo(expectedModifiedDate);
        dirForPreparation.update(Arrays.asList(
                newFontUpdateRequest("foo.ttf,1,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,2,bar", GOOD_SIGNATURE),
                newFontUpdateRequest("foo.ttf,3,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,4,bar", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family name='foobar'>"
                        + "  <font>foo.ttf</font>"
                        + "  <font>bar.ttf</font>"
                        + "</family>")));
        // Verifies that getLastModifiedTimeMillis() returns the value of currentTimeMillis.
        assertThat(dirForPreparation.getSystemFontConfig().getLastModifiedTimeMillis())
                .isEqualTo(CURRENT_TIME);
        // Four font dirs are created.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(4);
        assertThat(dirForPreparation.getSystemFontConfig().getLastModifiedTimeMillis())
                .isNotEqualTo(expectedModifiedDate);

        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        assertThat(dir.getPostScriptMap()).containsKey("foo");
        assertThat(parser.getRevision(dir.getPostScriptMap().get("foo"))).isEqualTo(3);
        assertThat(dir.getPostScriptMap()).containsKey("bar");
        assertThat(parser.getRevision(dir.getPostScriptMap().get("bar"))).isEqualTo(4);
        // Outdated font dir should be deleted.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(2);
        assertNamedFamilyExists(dir.getSystemFontConfig(), "foobar");
        assertThat(dir.getFontFamilyMap()).containsKey("foobar");
        FontConfig.FontFamily foobar = dir.getFontFamilyMap().get("foobar");
        assertThat(foobar.getFontList()).hasSize(2);
        assertThat(foobar.getFontList().get(0).getFile())
                .isEqualTo(dir.getPostScriptMap().get("foo"));
        assertThat(foobar.getFontList().get(1).getFile())
                .isEqualTo(dir.getPostScriptMap().get("bar"));
    }

    @Test
    public void construct_empty() {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        assertThat(dir.getPostScriptMap()).isEmpty();
        assertThat(dir.getFontFamilyMap()).isEmpty();
    }

    @Test
    public void construct_missingFsverity() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dirForPreparation.loadFontFileMap();
        dirForPreparation.update(Arrays.asList(
                newFontUpdateRequest("foo.ttf,1,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,2,bar", GOOD_SIGNATURE),
                newFontUpdateRequest("foo.ttf,3,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,4,bar", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family name='foobar'>"
                        + "  <font>foo.ttf</font>"
                        + "  <font>bar.ttf</font>"
                        + "</family>")));
        // Four font dirs are created.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(4);

        fakeFsverityUtil.remove(
                dirForPreparation.getPostScriptMap().get("foo").getAbsolutePath());
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        assertThat(dir.getPostScriptMap()).isEmpty();
        // All font dirs (including dir for "bar.ttf") should be deleted.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(0);
        assertThat(dir.getFontFamilyMap()).isEmpty();
    }

    @Test
    public void construct_fontNameMismatch() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dirForPreparation.loadFontFileMap();
        dirForPreparation.update(Arrays.asList(
                newFontUpdateRequest("foo.ttf,1,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,2,bar", GOOD_SIGNATURE),
                newFontUpdateRequest("foo.ttf,3,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,4,bar", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family name='foobar'>"
                        + "  <font>foo.ttf</font>"
                        + "  <font>bar.ttf</font>"
                        + "</family>")));
        // Four font dirs are created.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(4);

        // Overwrite "foo.ttf" with wrong contents.
        FileUtils.stringToFile(dirForPreparation.getPostScriptMap().get("foo"), "bar,4");

        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        assertThat(dir.getPostScriptMap()).isEmpty();
        // All font dirs (including dir for "bar.ttf") should be deleted.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(0);
        assertThat(dir.getFontFamilyMap()).isEmpty();
    }

    @Test
    public void construct_olderThanPreinstalledFont() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        Function<Map<String, File>, FontConfig> configSupplier = (map) -> {
            FontConfig.Font fooFont = new FontConfig.Font(
                    new File(mPreinstalledFontDirs.get(0), "foo.ttf"), null, "foo",
                    new FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT), 0, null, null);
            FontConfig.Font barFont = new FontConfig.Font(
                    new File(mPreinstalledFontDirs.get(1), "bar.ttf"), null, "bar",
                    new FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT), 0, null, null);

            FontConfig.FontFamily family = new FontConfig.FontFamily(
                    Arrays.asList(fooFont, barFont), "sans-serif", null,
                    FontConfig.FontFamily.VARIANT_DEFAULT);
            return new FontConfig(Collections.singletonList(family),
                    Collections.emptyList(), 0, 1);
        };

        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, configSupplier);
        dirForPreparation.loadFontFileMap();
        dirForPreparation.update(Arrays.asList(
                newFontUpdateRequest("foo.ttf,1,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,2,bar", GOOD_SIGNATURE),
                newFontUpdateRequest("foo.ttf,3,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,4,bar", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family name='foobar'>"
                        + "  <font>foo.ttf</font>"
                        + "  <font>bar.ttf</font>"
                        + "</family>")));
        // Four font dirs are created.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(4);

        // Add preinstalled fonts.
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(0), "foo.ttf"), "foo,5,foo");
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(1), "bar.ttf"), "bar,1,bar");
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(1), "bar.ttf"), "bar,2,bar");
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, configSupplier);
        dir.loadFontFileMap();
        // For foo.ttf, preinstalled font (revision 5) should be used.
        assertThat(dir.getPostScriptMap()).doesNotContainKey("foo");
        // For bar.ttf, updated font (revision 4) should be used.
        assertThat(dir.getPostScriptMap()).containsKey("bar");
        assertThat(parser.getRevision(dir.getPostScriptMap().get("bar"))).isEqualTo(4);
        // Outdated font dir should be deleted.
        // We don't delete bar.ttf in this case, because it's normal that OTA updates preinstalled
        // fonts.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(1);
        // Font family depending on obsoleted font should be removed.
        assertThat(dir.getFontFamilyMap()).isEmpty();
    }

    @Test
    public void construct_failedToLoadConfig() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                new File("/dev/null"), mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        assertThat(dir.getPostScriptMap()).isEmpty();
        assertThat(dir.getFontFamilyMap()).isEmpty();
    }

    @Test
    public void construct_afterBatchFailure() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dirForPreparation.loadFontFileMap();
        dirForPreparation.update(Arrays.asList(
                newFontUpdateRequest("foo.ttf,1,foo", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family name='foobar'>"
                        + "  <font>foo.ttf</font>"
                        + "</family>")));
        try {
            dirForPreparation.update(Arrays.asList(
                    newFontUpdateRequest("foo.ttf,2,foo", GOOD_SIGNATURE),
                    newFontUpdateRequest("bar.ttf,2,bar", "Invalid signature"),
                    newAddFontFamilyRequest("<family name='foobar'>"
                            + "  <font>foo.ttf</font>"
                            + "  <font>bar.ttf</font>"
                            + "</family>")));
            fail("Batch update with invalid signature should fail");
        } catch (FontManagerService.SystemFontException e) {
            // Expected
        }

        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        // The state should be rolled back as a whole if one of the update requests fail.
        assertThat(dir.getPostScriptMap()).containsKey("foo");
        assertThat(parser.getRevision(dir.getPostScriptMap().get("foo"))).isEqualTo(1);
        assertThat(dir.getFontFamilyMap()).containsKey("foobar");
        FontConfig.FontFamily foobar = dir.getFontFamilyMap().get("foobar");
        assertThat(foobar.getFontList()).hasSize(1);
        assertThat(foobar.getFontList().get(0).getFile())
                .isEqualTo(dir.getPostScriptMap().get("foo"));
    }

    @Test
    public void loadFontFileMap_twice() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("test");
        File fontFile = dir.getPostScriptMap().get("test");
        dir.loadFontFileMap();
        assertThat(dir.getPostScriptMap().get("test")).isEqualTo(fontFile);
    }

    @Test
    public void installFontFile() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("test");
        assertThat(parser.getRevision(dir.getPostScriptMap().get("test"))).isEqualTo(1);
        File fontFile = dir.getPostScriptMap().get("test");
        assertThat(Os.stat(fontFile.getAbsolutePath()).st_mode & 0777).isEqualTo(0644);
        File fontDir = fontFile.getParentFile();
        assertThat(Os.stat(fontDir.getAbsolutePath()).st_mode & 0777).isEqualTo(0711);
    }

    @Test
    public void installFontFile_upgrade() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                GOOD_SIGNATURE)));
        Map<String, File> mapBeforeUpgrade = dir.getPostScriptMap();
        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,2,test",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("test");
        assertThat(parser.getRevision(dir.getPostScriptMap().get("test"))).isEqualTo(2);
        assertThat(mapBeforeUpgrade).containsKey("test");
        assertWithMessage("Older fonts should not be deleted until next loadFontFileMap")
                .that(parser.getRevision(mapBeforeUpgrade.get("test"))).isEqualTo(1);
        // Check that updatedFontDirs is pruned.
        assertWithMessage("config.updatedFontDirs should only list latest active dirs")
                .that(readConfig(mConfigFile).updatedFontDirs)
                .containsExactly(dir.getPostScriptMap().get("test").getParentFile().getName());
    }

    @Test
    public void installFontFile_systemFontHasPSNameDifferentFromFileName() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();

        // Setup the environment that the system installed font file named "foo.ttf" has PostScript
        // name "bar".
        File file = new File(mPreinstalledFontDirs.get(0), "foo.ttf");
        FileUtils.stringToFile(file, "foo.ttf,1,bar");
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, (map) -> {
            FontConfig.Font font = new FontConfig.Font(
                    file, null, "bar", new FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT),
                    0, null, null);
            FontConfig.FontFamily family = new FontConfig.FontFamily(
                    Collections.singletonList(font), "sans-serif", null,
                    FontConfig.FontFamily.VARIANT_DEFAULT);
            return new FontConfig(Collections.singletonList(family),
                    Collections.emptyList(), 0, 1);
        });
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("bar.ttf,2,bar",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("bar");
        assertThat(dir.getPostScriptMap().size()).isEqualTo(1);
        assertThat(parser.getRevision(dir.getPostScriptMap().get("bar"))).isEqualTo(2);
        File fontFile = dir.getPostScriptMap().get("bar");
        assertThat(Os.stat(fontFile.getAbsolutePath()).st_mode & 0777).isEqualTo(0644);
        File fontDir = fontFile.getParentFile();
        assertThat(Os.stat(fontDir.getAbsolutePath()).st_mode & 0777).isEqualTo(0711);
    }

    @Test
    public void installFontFile_sameVersion() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                GOOD_SIGNATURE)));
        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("test");
        assertThat(parser.getRevision(dir.getPostScriptMap().get("test"))).isEqualTo(1);
    }

    @Test
    public void installFontFile_downgrade() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,2,test",
                GOOD_SIGNATURE)));
        try {
            dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                    GOOD_SIGNATURE)));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode()).isEqualTo(FontManager.RESULT_ERROR_DOWNGRADING);
        }
        assertThat(dir.getPostScriptMap()).containsKey("test");
        assertWithMessage("Font should not be downgraded to an older revision")
                .that(parser.getRevision(dir.getPostScriptMap().get("test"))).isEqualTo(2);
        // Check that updatedFontDirs is not updated.
        assertWithMessage("config.updatedFontDirs should only list latest active dirs")
                .that(readConfig(mConfigFile).updatedFontDirs)
                .containsExactly(dir.getPostScriptMap().get("test").getParentFile().getName());
    }

    @Test
    public void installFontFile_multiple() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("foo.ttf,1,foo",
                GOOD_SIGNATURE)));
        dir.update(Collections.singletonList(newFontUpdateRequest("bar.ttf,2,bar",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("foo");
        assertThat(parser.getRevision(dir.getPostScriptMap().get("foo"))).isEqualTo(1);
        assertThat(dir.getPostScriptMap()).containsKey("bar");
        assertThat(parser.getRevision(dir.getPostScriptMap().get("bar"))).isEqualTo(2);
    }

    @Test
    public void installFontFile_batch() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Arrays.asList(
                newFontUpdateRequest("foo.ttf,1,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,2,bar", GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("foo");
        assertThat(parser.getRevision(dir.getPostScriptMap().get("foo"))).isEqualTo(1);
        assertThat(dir.getPostScriptMap()).containsKey("bar");
        assertThat(parser.getRevision(dir.getPostScriptMap().get("bar"))).isEqualTo(2);
    }

    @Test
    public void installFontFile_invalidSignature() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        try {
            dir.update(
                    Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                            "Invalid signature")));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.RESULT_ERROR_VERIFICATION_FAILURE);
        }
        assertThat(dir.getPostScriptMap()).isEmpty();
    }

    @Test
    public void installFontFile_preinstalled_upgrade() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(0), "test.ttf"),
                "test.ttf,1,test");
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,2,test",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("test");
        assertThat(parser.getRevision(dir.getPostScriptMap().get("test"))).isEqualTo(2);
    }

    @Test
    public void installFontFile_preinstalled_sameVersion() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(0), "test.ttf"),
                "test.ttf,1,test");
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("test");
        assertThat(parser.getRevision(dir.getPostScriptMap().get("test"))).isEqualTo(1);
    }

    @Test
    public void installFontFile_preinstalled_downgrade() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        File file = new File(mPreinstalledFontDirs.get(0), "test.ttf");
        FileUtils.stringToFile(file, "test.ttf,2,test");
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, (map) -> {
            FontConfig.Font font = new FontConfig.Font(
                    file, null, "test", new FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT), 0, null,
                    null);
            FontConfig.FontFamily family = new FontConfig.FontFamily(
                    Collections.singletonList(font), "sans-serif", null,
                    FontConfig.FontFamily.VARIANT_DEFAULT);
            return new FontConfig(Collections.singletonList(family), Collections.emptyList(), 0, 1);
        });
        dir.loadFontFileMap();

        try {
            dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                    GOOD_SIGNATURE)));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode()).isEqualTo(FontManager.RESULT_ERROR_DOWNGRADING);
        }
        assertThat(dir.getPostScriptMap()).isEmpty();
    }

    @Test
    public void installFontFile_failedToWriteConfigXml() throws Exception {
        long expectedModifiedDate = 1234567890;
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(0), "test.ttf"),
                "test.ttf,1,test");

        File readonlyDir = new File(mCacheDir, "readonly");
        assertThat(readonlyDir.mkdir()).isTrue();
        File readonlyFile = new File(readonlyDir, "readonly_config.xml");

        PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
        config.lastModifiedMillis = expectedModifiedDate;
        writeConfig(config, readonlyFile);

        assertThat(readonlyDir.setWritable(false, false)).isTrue();
        try {
            UpdatableFontDir dir = new UpdatableFontDir(
                    mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                    readonlyFile, mCurrentTimeSupplier, mConfigSupplier);
            dir.loadFontFileMap();

            try {
                dir.update(
                        Collections.singletonList(newFontUpdateRequest("test.ttf,2,test",
                                GOOD_SIGNATURE)));
            } catch (FontManagerService.SystemFontException e) {
                assertThat(e.getErrorCode())
                        .isEqualTo(FontManager.RESULT_ERROR_FAILED_UPDATE_CONFIG);
            }
            assertThat(dir.getSystemFontConfig().getLastModifiedTimeMillis())
                    .isEqualTo(expectedModifiedDate);
            assertThat(dir.getPostScriptMap()).isEmpty();
        } finally {
            assertThat(readonlyDir.setWritable(true, true)).isTrue();
        }
    }

    @Test
    public void installFontFile_failedToParsePostScript() throws Exception {
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir,
                new UpdatableFontDir.FontFileParser() {

                    @Override
                    public String getPostScriptName(File file) throws IOException {
                        return null;
                    }

                    @Override
                    public String buildFontFileName(File file) throws IOException {
                        return null;
                    }

                    @Override
                    public long getRevision(File file) throws IOException {
                        return 0;
                    }

                    @Override
                    public void tryToCreateTypeface(File file) throws IOException {
                    }
                }, fakeFsverityUtil, mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        try {
            dir.update(Collections.singletonList(newFontUpdateRequest("foo.ttf,1,foo",
                    GOOD_SIGNATURE)));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.RESULT_ERROR_INVALID_FONT_NAME);
        }
        assertThat(dir.getPostScriptMap()).isEmpty();
    }

    @Test
    public void installFontFile_failedToParsePostScriptName_invalidFont() throws Exception {
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir,
                new UpdatableFontDir.FontFileParser() {
                    @Override
                    public String getPostScriptName(File file) throws IOException {
                        throw new IOException();
                    }

                    @Override
                    public String buildFontFileName(File file) throws IOException {
                        throw new IOException();
                    }

                    @Override
                    public long getRevision(File file) throws IOException {
                        return 0;
                    }

                    @Override
                    public void tryToCreateTypeface(File file) throws IOException {
                    }
                }, fakeFsverityUtil, mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        try {
            dir.update(Collections.singletonList(newFontUpdateRequest("foo.ttf,1,foo",
                    GOOD_SIGNATURE)));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.RESULT_ERROR_INVALID_FONT_FILE);
        }
        assertThat(dir.getPostScriptMap()).isEmpty();
    }

    @Test
    public void installFontFile_failedToCreateTypeface() throws Exception {
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        FakeFontFileParser parser = new FakeFontFileParser();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir,
                new UpdatableFontDir.FontFileParser() {
                    @Override
                    public String getPostScriptName(File file) throws IOException {
                        return parser.getPostScriptName(file);
                    }

                    @Override
                    public String buildFontFileName(File file) throws IOException {
                        return parser.buildFontFileName(file);
                    }

                    @Override
                    public long getRevision(File file) throws IOException {
                        return parser.getRevision(file);
                    }

                    @Override
                    public void tryToCreateTypeface(File file) throws IOException {
                        throw new IOException();
                    }
                }, fakeFsverityUtil, mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        try {
            dir.update(Collections.singletonList(newFontUpdateRequest("foo.ttf,1,foo",
                    GOOD_SIGNATURE)));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.RESULT_ERROR_INVALID_FONT_FILE);
        }
        assertThat(dir.getPostScriptMap()).isEmpty();
    }

    @Test
    public void installFontFile_renameToPsNameFailure() throws Exception {
        UpdatableFontDir.FsverityUtil fakeFsverityUtil = new UpdatableFontDir.FsverityUtil() {
            private final FakeFsverityUtil mFake = new FakeFsverityUtil();

            @Override
            public boolean hasFsverity(String path) {
                return mFake.hasFsverity(path);
            }

            @Override
            public void setUpFsverity(String path, byte[] pkcs7Signature) throws IOException {
                mFake.setUpFsverity(path, pkcs7Signature);
            }

            @Override
            public boolean rename(File src, File dest) {
                return false;
            }
        };
        FakeFontFileParser parser = new FakeFontFileParser();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        try {
            dir.update(Collections.singletonList(newFontUpdateRequest("foo.ttf,1,foo",
                    GOOD_SIGNATURE)));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE);
        }
        assertThat(dir.getPostScriptMap()).isEmpty();
    }

    @Test
    public void installFontFile_batchFailure() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("foo.ttf,1,foo",
                GOOD_SIGNATURE)));
        try {
            dir.update(Arrays.asList(
                    newFontUpdateRequest("foo.ttf,2,foo", GOOD_SIGNATURE),
                    newFontUpdateRequest("bar.ttf,2,bar", "Invalid signature")));
            fail("Batch update with invalid signature should fail");
        } catch (FontManagerService.SystemFontException e) {
            // Expected
        }
        // The state should be rolled back as a whole if one of the update requests fail.
        assertThat(dir.getPostScriptMap()).containsKey("foo");
        assertThat(parser.getRevision(dir.getPostScriptMap().get("foo"))).isEqualTo(1);
    }

    @Test
    public void addFontFamily() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Arrays.asList(
                newFontUpdateRequest("test.ttf,1,test", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family name='test'>"
                        + "  <font>test.ttf</font>"
                        + "</family>")));
        assertThat(dir.getPostScriptMap()).containsKey("test");
        assertThat(dir.getFontFamilyMap()).containsKey("test");
        FontConfig.FontFamily test = dir.getFontFamilyMap().get("test");
        assertThat(test.getFontList()).hasSize(1);
        assertThat(test.getFontList().get(0).getFile())
                .isEqualTo(dir.getPostScriptMap().get("test"));
    }

    @Test
    public void addFontFamily_noName() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        List<FontUpdateRequest> requests = Arrays.asList(
                newFontUpdateRequest("test.ttf,1,test", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family lang='en'>"
                        + "  <font>test.ttf</font>"
                        + "</family>"));
        try {
            dir.update(requests);
            fail("Expect NullPointerException");
        } catch (NullPointerException e) {
            // Expect
        }
    }

    @Test
    public void addFontFamily_fontNotAvailable() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        try {
            dir.update(Arrays.asList(newAddFontFamilyRequest("<family name='test'>"
                    + "  <font>test.ttf</font>"
                    + "</family>")));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.RESULT_ERROR_FONT_NOT_FOUND);
        }
    }

    @Test
    public void getSystemFontConfig() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        // We assume we have monospace.
        assertNamedFamilyExists(dir.getSystemFontConfig(), "monospace");

        dir.update(Arrays.asList(
                newFontUpdateRequest("test.ttf,1,test", GOOD_SIGNATURE),
                // Updating an existing font family.
                newAddFontFamilyRequest("<family name='monospace'>"
                        + "  <font>test.ttf</font>"
                        + "</family>"),
                // Adding a new font family.
                newAddFontFamilyRequest("<family name='test'>"
                        + "  <font>test.ttf</font>"
                        + "</family>")));
        FontConfig fontConfig = dir.getSystemFontConfig();
        assertNamedFamilyExists(fontConfig, "monospace");
        FontConfig.FontFamily monospace = getLastFamily(fontConfig, "monospace");
        assertThat(monospace.getFontList()).hasSize(1);
        assertThat(monospace.getFontList().get(0).getFile())
                .isEqualTo(dir.getPostScriptMap().get("test"));
        assertNamedFamilyExists(fontConfig, "test");
        assertThat(getLastFamily(fontConfig, "test").getFontList())
                .isEqualTo(monospace.getFontList());
    }

    @Test
    public void getSystemFontConfig_preserveFirstFontFamily() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        assertThat(dir.getSystemFontConfig().getFontFamilies()).isNotEmpty();
        FontConfig.FontFamily firstFontFamily = dir.getSystemFontConfig().getFontFamilies().get(0);
        assertThat(firstFontFamily.getName()).isNotEmpty();

        dir.update(Arrays.asList(
                newFontUpdateRequest("test.ttf,1,test", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family name='" + firstFontFamily.getName() + "'>"
                        + "  <font>test.ttf</font>"
                        + "</family>")));
        FontConfig fontConfig = dir.getSystemFontConfig();
        assertThat(dir.getSystemFontConfig().getFontFamilies()).isNotEmpty();
        assertThat(fontConfig.getFontFamilies().get(0)).isEqualTo(firstFontFamily);
        FontConfig.FontFamily updated = getLastFamily(fontConfig, firstFontFamily.getName());
        assertThat(updated.getFontList()).hasSize(1);
        assertThat(updated.getFontList().get(0).getFile())
                .isEqualTo(dir.getPostScriptMap().get("test"));
        assertThat(updated).isNotEqualTo(firstFontFamily);
    }

    private FontUpdateRequest newFontUpdateRequest(String content, String signature)
            throws Exception {
        File file = File.createTempFile("font", "ttf", mCacheDir);
        FileUtils.stringToFile(file, content);
        return new FontUpdateRequest(
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                signature.getBytes());
    }

    private static FontUpdateRequest newAddFontFamilyRequest(String xml) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        ByteArrayInputStream is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        parser.setInput(is, "UTF-8");
        parser.nextTag();

        FontConfig.FontFamily fontFamily = FontListParser.readFamily(parser, "", null, true);
        List<FontUpdateRequest.Font> fonts = new ArrayList<>();
        for (FontConfig.Font font : fontFamily.getFontList()) {
            String name = font.getFile().getName();
            String psName = name.substring(0, name.length() - 4);  // drop suffix
            FontUpdateRequest.Font updateFont = new FontUpdateRequest.Font(
                    psName, font.getStyle(), font.getTtcIndex(), font.getFontVariationSettings());
            fonts.add(updateFont);
        }
        FontUpdateRequest.Family family = new FontUpdateRequest.Family(fontFamily.getName(), fonts);
        return new FontUpdateRequest(family);
    }

    private static PersistentSystemFontConfig.Config readConfig(File file) throws Exception {
        PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
        try (InputStream is = new FileInputStream(file)) {
            PersistentSystemFontConfig.loadFromXml(is, config);
        }
        return config;
    }

    private static void writeConfig(PersistentSystemFontConfig.Config config,
            File file) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            PersistentSystemFontConfig.writeToXml(fos, config);
        }
    }

    // Returns the last family with the given name, which will be used for creating Typeface.
    private static FontConfig.FontFamily getLastFamily(FontConfig fontConfig, String familyName) {
        List<FontConfig.FontFamily> fontFamilies = fontConfig.getFontFamilies();
        for (int i = fontFamilies.size() - 1; i >= 0; i--) {
            if (familyName.equals(fontFamilies.get(i).getName())) {
                return fontFamilies.get(i);
            }
        }
        return null;
    }

    private static void assertNamedFamilyExists(FontConfig fontConfig, String familyName) {
        assertThat(fontConfig.getFontFamilies().stream()
                .map(FontConfig.FontFamily::getName)
                .collect(Collectors.toSet())).contains(familyName);
    }
}
