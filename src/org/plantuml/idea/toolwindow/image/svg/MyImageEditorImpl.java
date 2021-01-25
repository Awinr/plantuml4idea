/*
 * Copyright 2004-2005 Alexey Efimov
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
package org.plantuml.idea.toolwindow.image.svg;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.RefreshQueue;
import com.intellij.ui.scale.ScaleContext;
import com.intellij.ui.scale.ScaleType;
import com.intellij.util.ImageLoader;
import org.intellij.images.editor.ImageDocument;
import org.intellij.images.editor.ImageEditor;
import org.intellij.images.editor.ImageZoomModel;
import org.intellij.images.fileTypes.ImageFileTypeManager;
import org.intellij.images.thumbnail.actionSystem.ThumbnailViewActions;
import org.jetbrains.annotations.NotNull;
import org.plantuml.idea.lang.settings.PlantUmlSettings;
import org.plantuml.idea.toolwindow.Zoom;
import org.plantuml.idea.toolwindow.image.svg.batik.MySvgDocumentFactoryKt;
import org.plantuml.idea.toolwindow.image.svg.batik.MySvgTranscoder;
import org.w3c.dom.Document;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Image viewer implementation.
 *
 * @author <a href="mailto:aefimov.box@gmail.com">Alexey Efimov</a>
 */
public final class MyImageEditorImpl implements ImageEditor {
    private static final Logger LOG = Logger.getInstance(MyImageEditorImpl.class);

    private final Project project;
    private final VirtualFile file;
    private final MyImageEditorUI editorUI;
    private boolean disposed;

//  public MyImageEditorImpl(@NotNull Project project, @NotNull VirtualFile file) {
//    this(project, file, false);
//  }

    /**
     * @param isEmbedded if it's true the toolbar and the image info are disabled and an image is left-side aligned
     * @param zoomModel
     */
    public MyImageEditorImpl(@NotNull Project project, @NotNull VirtualFile file, boolean isEmbedded, Zoom zoomModel) {
        this.project = project;
        this.file = file;

        editorUI = new MyImageEditorUI(this, isEmbedded, zoomModel);
        Disposer.register(this, editorUI);

        VirtualFileManager.getInstance().addVirtualFileListener(new VirtualFileListener() {
            @Override
            public void propertyChanged(@NotNull VirtualFilePropertyEvent event) {
                MyImageEditorImpl.this.propertyChanged(event);
            }

            @Override
            public void contentsChanged(@NotNull VirtualFileEvent event) {
                MyImageEditorImpl.this.contentsChanged(event);
            }
        }, this);

        setValue(file);
    }

    void setValue(VirtualFile file) {
        try {
            //CUSTOM
            editorUI.setImageProvider(new MyScaledImageProvider(file), "svg");
//            editorUI.setImageProvider(IfsUtil.getImageProvider(file), IfsUtil.getFormat(file));

        } catch (Exception e) {
            LOG.error(e);
            //     Error loading image file
            editorUI.setImageProvider(null, null);
        }
    }

    @Override
    public boolean isValid() {
        ImageDocument document = editorUI.getImageComponent().getDocument();
        return document.getValue() != null;
    }

    @Override
    public MyImageEditorUI getComponent() {
        return editorUI;
    }

    @Override
    public JComponent getContentComponent() {
        return editorUI.getImageComponent();
    }

    @Override
    @NotNull
    public VirtualFile getFile() {
        return file;
    }

    @Override
    @NotNull
    public Project getProject() {
        return project;
    }

    @Override
    public ImageDocument getDocument() {
        return editorUI.getImageComponent().getDocument();
    }

    @Override
    public void setTransparencyChessboardVisible(boolean visible) {
        //CUSTOM
        PlantUmlSettings.getInstance().setShowChessboard(visible);

        editorUI.getImageComponent().setTransparencyChessboardVisible(visible);
        editorUI.repaint();
    }

    @Override
    public boolean isTransparencyChessboardVisible() {
        return editorUI.getImageComponent().isTransparencyChessboardVisible();
    }

    @Override
    public boolean isEnabledForActionPlace(String place) {
        // Disable for thumbnails action
        return !ThumbnailViewActions.ACTION_PLACE.equals(place);
    }

    @Override
    public void setGridVisible(boolean visible) {
        editorUI.getImageComponent().setGridVisible(visible);
        editorUI.repaint();
    }

    @Override
    public void setEditorBackground(Color color) {
        editorUI.getImageComponent().getParent().setBackground(color);
    }

    @Override
    public void setBorderVisible(boolean visible) {
        editorUI.getImageComponent().setBorderVisible(visible);
    }

    @Override
    public boolean isGridVisible() {
        return editorUI.getImageComponent().isGridVisible();
    }

    @Override
    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public ImageZoomModel getZoomModel() {
        return editorUI.getZoomModel();
    }

    @Override
    public void dispose() {
        disposed = true;
    }

    void propertyChanged(@NotNull VirtualFilePropertyEvent event) {
        if (file.equals(event.getFile())) {
            // Change document
            file.refresh(true, false, () -> {
                if (ImageFileTypeManager.getInstance().isImage(file)) {
                    setValue(file);
                } else {
                    setValue(null);
                    // Close editor
                    FileEditorManager editorManager = FileEditorManager.getInstance(project);
                    editorManager.closeFile(file);
                }
            });
        }
    }

    void contentsChanged(@NotNull VirtualFileEvent event) {
        if (file.equals(event.getFile())) {
            // Change document
            Runnable postRunnable = () -> setValue(file);
            RefreshQueue.getInstance().refresh(true, false, postRunnable, ModalityState.current(), file);
        }
    }

    public class MyScaledImageProvider implements ImageDocument.ScaledImageProvider {

        private final VirtualFile file;

        private volatile boolean renderingInProgress;

        private volatile Holder holder = new Holder();

        public MyScaledImageProvider(VirtualFile file) {
            this.file = file;
        }

        public Holder getHolder() {
            return holder;
        }

        @Override
        public BufferedImage apply(Double bullshitScale, Component component) {
            double zoom = getZoomModel().getZoomFactor();
            Holder holder = this.holder;
            if (holder.isChanged(zoom) && !renderingInProgress) {
                createImage(component, zoom);
            }
            return holder.image;
        }

        public synchronized void createImage(Component component, double zoom) {
            Holder holder = this.holder;
            if (holder.image != null && holder.zoom.equals(zoom)) {
                return;
            }
            try {
                renderingInProgress = true;
                long start = System.currentTimeMillis();
                ByteArrayInputStream in = new ByteArrayInputStream(file.contentsToByteArray());
                InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                Document svgDocument = MySvgDocumentFactoryKt.createSvgDocument(null, reader);
                //it shows what is in png document - unZOOMED values, not limited by px limit
                ImageLoader.Dimension2DDouble outSize = new ImageLoader.Dimension2DDouble(0.0D, 0.0D);

                ScaleContext scaleContext = ScaleContext.create(component);

                double scale = scaleContext.getScale(ScaleType.SYS_SCALE);
                double scaledZoom;
                if (PlantUmlSettings.getInstance().isSvgPreviewScaling()) {
                    scaledZoom = zoom * scale;
                } else {
                    scaledZoom = zoom;
                }

                BufferedImage image = MySvgTranscoder.createImage((float) scaledZoom, svgDocument, outSize);

                Holder newHolder = new Holder(image, outSize, zoom);

                this.holder = newHolder;
                LOG.debug("image created in ", System.currentTimeMillis() - start, "ms", " zoom=", zoom, " scale=", scale, " width=", newHolder.image.getWidth(), " hight=", newHolder.image.getHeight(), " docWidth=", newHolder.outSize.getWidth(), " docHight=", newHolder.outSize.getHeight());
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                renderingInProgress = false;
            }
        }

        class Holder {
            private final ImageLoader.Dimension2DDouble outSize;
            private final Double zoom;
            private final BufferedImage image;

            public Holder(BufferedImage image, ImageLoader.Dimension2DDouble outSize, Double zoom) {
                this.outSize = outSize;
                this.zoom = zoom;
                this.image = image;
            }

            public Holder() {
                this(null, null, -1.0);
            }

            private boolean isChanged(double zoom) {
                return image == null || !this.zoom.equals(zoom);
            }

            public ImageLoader.Dimension2DDouble getOutSize() {
                return outSize;
            }

            public Double getZoom() {
                return zoom;
            }

            public BufferedImage getImage() {
                return image;
            }

            @Override
            public String toString() {
                return "Holder{" +
                        "outSize=" + outSize +
                        ", zoom=" + zoom +
                        ", image=" + image +
                        '}';
            }


        }
    }
}
