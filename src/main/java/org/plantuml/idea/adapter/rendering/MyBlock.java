package org.plantuml.idea.adapter.rendering;

import com.intellij.openapi.diagnostic.Logger;
import net.sourceforge.plantuml.*;
import net.sourceforge.plantuml.klimt.creole.Display;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.jetbrains.annotations.NotNull;
import org.plantuml.idea.plantuml.ImageFormat;
import org.plantuml.idea.rendering.RenderRequest;

import java.util.List;
/// 封装单幅图的信息
public class MyBlock {
    private static final Logger LOG = Logger.getInstance(MyBlock.class);

    private final String fileOrDirname;
    private final Titles titles;
    private BlockUml blockUml;///源代码文件可以包含多个 UML 图，每个图表之间通过 @startuml 和 @enduml 标记来划分 BlockUml封装了从 PlantUML 源代码解析出的单个 UML 图
    private int nbImages;// 图标总页数
    private net.sourceforge.plantuml.core.Diagram diagram;// 它代表一个解析后的 UML 图

    public MyBlock(BlockUml block) {
        this.blockUml = block;
        diagram = block.getDiagram();
        nbImages = diagram.getNbImages();
        fileOrDirname = block.getFileOrDirname();
        titles = new Titles(diagram);
    }


    public String getFilename() {
        return fileOrDirname;
    }

    public Titles getTitles() {
        return titles;
    }

    @NotNull
    public static ScaleSimple calculateScale(int zoom, Scale scale) {
        return new ScaleSimple(getPlantUmlScale(scale) * zoom / 100f);
    }

    private static double getPlantUmlScale(Scale scale) {
        double plantUmlScale = 1.0;
        if (scale instanceof ScaleSimple) {
            plantUmlScale = scale.getScale(1, 1);
        }
        return plantUmlScale;
    }

    public String getFileOrDirname() {
        return fileOrDirname;
    }

    public BlockUml getBlockUml() {
        return blockUml;
    }

    public int getNbImages() {
        return nbImages;
    }

    public net.sourceforge.plantuml.core.Diagram getDiagram() {
        return diagram;
    }


    protected static void addTitle(List<String> titles, Display display) {
        if (display.size() > 0) {
            titles.add(display.toString());
        } else {
            titles.add(null);
        }
    }


    public void zoomDiagram(RenderRequest renderRequest) {
        if (renderRequest.getFormat() == ImageFormat.SVG && renderRequest.isDisableSvgZoom()) {
            LOG.debug("skipping SVG zooming");
            return;
        }
        long start = System.currentTimeMillis();
        int osScaledZoom = renderRequest.getZoom().getScaledZoom();

        if (diagram instanceof NewpagedDiagram) {
            NewpagedDiagram newpagedDiagram = (NewpagedDiagram) diagram;
            for (net.sourceforge.plantuml.core.Diagram page : newpagedDiagram.getDiagrams()) {
                if (page instanceof AbstractPSystem) {
                    AbstractPSystem descriptionDiagram = (AbstractPSystem) page;
                    Scale scale = descriptionDiagram.getScale();

                    if (scale == null || scale instanceof ScaleSimple || osScaledZoom != 100) {
                        descriptionDiagram.setScale(calculateScale(osScaledZoom, scale));
                    }
                }
            }
        } else if (diagram instanceof AbstractPSystem) { //gantt, salt wireframe - but has no effect
            AbstractPSystem d = (AbstractPSystem) diagram;
            Scale scale = d.getScale();

            if (scale == null || scale instanceof ScaleSimple || osScaledZoom != 100) {
                d.setScale(calculateScale(osScaledZoom, scale));
            }
        }
        LOG.debug("zoom diagram done in  ", System.currentTimeMillis() - start, " ms");
    }


    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .append("fileOrDirname", fileOrDirname)
                .append("titles", titles)
                .append("blockUml", blockUml)
                .append("nbImages", nbImages)
                .append("diagram", diagram)
                .toString();
    }
}
