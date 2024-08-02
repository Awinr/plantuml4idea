package org.plantuml.idea.adapter.rendering;

import com.intellij.openapi.diagnostic.Logger;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.preproc.Defines;
import org.apache.commons.lang3.StringUtils;
import org.plantuml.idea.adapter.Utils;
import org.plantuml.idea.lang.annotator.LanguageDescriptor;
import org.plantuml.idea.rendering.RenderCacheItem;
import org.plantuml.idea.rendering.RenderRequest;
import org.plantuml.idea.rendering.RenderResult;
import org.plantuml.idea.settings.PlantUmlSettings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class PlantUmlRendererUtil {
    private static final Logger logger = Logger.getInstance(PlantUmlRendererUtil.class);
    // 用于从文本中找到标记为“newpage”的位置。这些标记通常用于指示文档中的分页或分节。
    public static final Pattern NEW_PAGE_PATTERN = Pattern.compile("\\n\\s*@?(?i)(newpage)(\\p{Blank}+[^\\n]+|\\p{Blank}*)(?=\\n)");

    private static final PlantUmlNormalRenderer NORMAL_RENDERER = new PlantUmlNormalRenderer();
    private static final PlantUmlExporter EXPORTER = new PlantUmlExporter();

    public static void renderAndSave(RenderRequest renderRequest, String path, String pathPrefix)
            throws IOException {
        Utils.prepareEnvironment(renderRequest.getProject(), renderRequest.getSourceFilePath());

        EXPORTER.renderAndSave(renderRequest, path, pathPrefix);
    }

    public static RenderResult render(RenderRequest renderRequest, RenderCacheItem cachedItem) {
        Utils.prepareEnvironment(renderRequest.getProject(), renderRequest.getSourceFilePath());

        long start = System.currentTimeMillis();
        String source = renderRequest.getSource();
        String[] sourceSplit = NEW_PAGE_PATTERN.split(source);
        logger.debug("split done ", System.currentTimeMillis() - start, "ms");
        // 检查是否为部分渲染
        boolean partialRender = sourceSplit[0].contains(LanguageDescriptor.IDEA_PARTIAL_RENDER);
        logger.debug("partialRender ", partialRender);

        start = System.currentTimeMillis();
        RenderResult renderResult;
        if (partialRender) {
            //使用内部 PlantUML 类进行部分渲染
            renderResult = new PlantUmlPartialRenderer().partialRender(renderRequest, cachedItem, sourceSplit);
        } else {
            // 使用正常的渲染器进行渲染
            renderResult = NORMAL_RENDERER.doRender(renderRequest, cachedItem, sourceSplit);
        }
        logger.debug("doRender ", System.currentTimeMillis() - start, "ms");

        return renderResult;
    }


    public static SourceStringReader newSourceStringReader(String source, RenderRequest renderRequest) {
        File file = renderRequest.getSourceFile();
        long start = System.currentTimeMillis();
        List<String> configAsList;
        String encoding;
        if (renderRequest.isTestRequest()) {
            encoding = "UTF-8";
            configAsList = new ArrayList<>();
        } else {
            PlantUmlSettings settings = PlantUmlSettings.getInstance();
            encoding = settings.getEncoding();
            if (StringUtils.isBlank(encoding)) {
                encoding = null;
            }
            configAsList = settings.getConfigAsList();
        }

        Defines defines;
        if (file != null) {
            defines = Defines.createWithFileName(file);
        } else {
            defines = Defines.createEmpty();
        }
        SourceStringReader sourceStringReader = new SourceStringReader(defines, source, encoding, configAsList);
        logger.debug("newSourceStringReader ", System.currentTimeMillis() - start, "ms, encoding=", encoding);
        return sourceStringReader;
    }


}
