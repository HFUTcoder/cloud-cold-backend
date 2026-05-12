package com.shenchen.cloudcoldagent.document.extract.reader;

import com.shenchen.cloudcoldagent.constant.KnowledgeChunkConstant;
import com.shenchen.cloudcoldagent.prompts.KnowledgePrompts;
import com.shenchen.cloudcoldagent.model.entity.record.knowledge.DocumentReadResult;
import com.shenchen.cloudcoldagent.model.entity.record.knowledge.ExtractedDocumentImage;
import com.shenchen.cloudcoldagent.config.properties.PdfMultimodalProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PDF多模态内容处理器
 * 负责处理PDF文件中的文字和图片内容，将其按照在页面中的位置顺序提取并整合
 * 使用PDFBox库解析PDF内容，并对图片进行识别转换为文本描述
 * <p>
 * 主要功能：
 * 1. 按页面顺序逐页处理PDF文档
 * 2. 提取文字和图片，并记录它们的坐标位置
 * 3. 根据Y轴坐标（从上到下）和X轴坐标（从左到右）对内容进行排序
 * 4. 将图片转换为文本描述，保持阅读顺序的连贯性
 */
@Component
@Slf4j
public class PdfMultimodalProcessor implements DocumentReaderStrategy {

    private final PdfMultimodalProperties pdfMultimodalProperties;

    /**
     * 注入 PDF 多模态处理所需的模型配置。
     *
     * @param pdfMultimodalProperties PDF 多模态识别配置。
     */
    public PdfMultimodalProcessor(PdfMultimodalProperties pdfMultimodalProperties) {
        this.pdfMultimodalProperties = pdfMultimodalProperties;
    }

    /**
     * 判断当前文件是否由该处理器负责读取。
     *
     * @param file 待处理文件。
     * @return 文件扩展名为 pdf 时返回 true。
     */
    @Override
    public boolean supports(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".pdf");
    }

    /**
     * 读取 PDF 文件并输出文档正文与抽取到的图片结果。
     *
     * @param file 待读取的 PDF 文件。
     * @return 文本内容与图片提取结果组成的读取结果。
     * @throws IOException 读取或解析 PDF 失败时抛出。
     */
    @Override
    public DocumentReadResult read(File file) throws IOException {
        try {
            PdfProcessingResult processingResult = processPdf(file);
            return new DocumentReadResult(
                    List.of(new Document(buildDocumentId(file), processingResult.content(), buildMetadata(file))),
                    processingResult.extractedImages()
            );
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("读取 PDF 文件失败: " + file.getName(), e);
        }
    }

    /**
     * 逐页解析 PDF，按阅读顺序整合文本并同步抽取图片描述。
     *
     * @param pdfFile 待处理的 PDF 文件。
     * @return 按页面顺序整理后的正文和图片列表。
     * @throws Exception PDF 解析或图片识别过程中发生异常时抛出。
     */
    public PdfProcessingResult processPdf(File pdfFile) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            int totalPages = document.getNumberOfPages();
            StringBuilder finalText = new StringBuilder();
            List<ExtractedDocumentImage> extractedImages = new ArrayList<>();
            AtomicInteger imageIndexCounter = new AtomicInteger(0);
            log.info("开始处理PDF文件: {}, 总页数: {}", pdfFile.getName(), totalPages);

            // 逐页处理PDF文档
            for (int pageNum = 0; pageNum < totalPages; pageNum++) {
                PDPage page = document.getPage(pageNum);
                // 获取页面高度，用于坐标系转换（PDF坐标系原点在左下角，需要转换为从上到下的阅读顺序）
                float pageHeight = page.getMediaBox().getHeight();

                // 创建统一内容提取器，在一个生命周期内同时捕获图片和文字
                UnifiedContentStripper stripper = new UnifiedContentStripper(
                        pageHeight,
                        pageNum + 1,
                        imageIndexCounter,
                        extractedImages
                );
                // 设置只处理当前页（PDFBox页码从1开始）
                stripper.setStartPage(pageNum + 1);
                stripper.setEndPage(pageNum + 1);
                // getText方法会触发内部的processOperator（捕获图片）和writeString（捕获文字）
                stripper.getText(document);

                // 获取当前页面提取到的所有内容元素（文字和图片）
                List<ContentElement> allElements = stripper.getElements();

                // 全局排序：按照Y轴坐标从上到下、X轴坐标从左到右排序
                // y0是相对于底部的距离，所以e2.y0 - e1.y0的结果是"从上到下"
                allElements.sort((e1, e2) -> {
                    // 如果Y轴坐标差距大于5像素，则认为不在同一行，按Y轴排序（从上到下）
                    if (Math.abs(e1.getY0() - e2.getY0()) > 5) {
                        return Integer.compare(e2.getY0(), e1.getY0());
                    }
                    // 如果在同一行（Y轴坐标差距小于等于5像素），则按X轴坐标排序（从左到右）
                    return Integer.compare(e1.getX0(), e2.getX0());
                });

                // 按照排序后的顺序，将文字和图片占位符按原位拼接到最终文本中
                for (ContentElement element : allElements) {
                    finalText.append(element.getContent()).append("\n");
                }
                // 每页处理完后添加一个空行分隔
                finalText.append("\n");
            }
            log.info("PDF处理完成");

            // 将 [IMAGE_N] 占位符替换为实际的 AI 图像描述
            String text = finalText.toString().trim();
            for (ExtractedDocumentImage img : extractedImages) {
                String placeholder = KnowledgeChunkConstant.IMAGE_PLACEHOLDER_PREFIX + img.imageIndex() + KnowledgeChunkConstant.IMAGE_PLACEHOLDER_SUFFIX;
                String desc = img.description();
                if (desc == null || desc.isBlank()) {
                    text = text.replace(placeholder, "");
                } else {
                    desc = desc.replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
                    text = text.replace(
                            placeholder,
                            KnowledgeChunkConstant.IMAGE_TAG_PREFIX + img.imageIndex() + KnowledgeChunkConstant.IMAGE_TAG_SUFFIX + escapeXml(desc) + KnowledgeChunkConstant.IMAGE_TAG_CLOSE
                    );
                }
            }
            return new PdfProcessingResult(text, extractedImages);
        }
    }

    /**
     * 处理单张图片：上传 MinIO + AI 识别 + 返回 cloudcoldagent-image 标签
     */
    private ExtractedDocumentImage processImage(PDImageXObject image, int pageNumber, int imageIndex) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BufferedImage bufferedImage = image.getImage();
            ImageIO.write(bufferedImage, "png", baos);
            byte[] imageBytes = baos.toByteArray();
            String description = image2Text(imageBytes);
            description = (description == null || description.trim().isEmpty()) ? "" : description.trim();
            return new ExtractedDocumentImage(
                    imageIndex,
                    pageNumber,
                    imageBytes,
                    MimeTypeUtils.IMAGE_PNG_VALUE,
                    description
            );
        } catch (Exception e) {
            log.error("图片处理异常", e);
            return new ExtractedDocumentImage(
                    imageIndex,
                    pageNumber,
                    new byte[0],
                    MimeTypeUtils.IMAGE_PNG_VALUE,
                    ""
            );
        }
    }

    /**
     * 对 XML 特殊字符进行转义。
     *
     * @param text 原始文本。
     * @return 转义后的文本。
     */
    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private OpenAiChatModel multimodalChatModel;

    /**
     * 初始化用于图片理解的多模态模型客户端。
     */
    @PostConstruct
    public void init() {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .temperature(pdfMultimodalProperties.getTemperature())
                .model(pdfMultimodalProperties.getModel())
                .build();
        multimodalChatModel = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(pdfMultimodalProperties.getBaseUrl())
                        .apiKey(new SimpleApiKey(pdfMultimodalProperties.getApiKey()))
                        .build())
                .defaultOptions(options)
                .build();
    }


    /**
     * 调用多模态模型将图片内容转换成文本描述。
     *
     * @param imageBytes PNG 图片的原始字节数组。
     * @return 模型输出的图片描述。
     */
    private String image2Text(byte[] imageBytes) {
        ByteArrayResource imageResource = new ByteArrayResource(imageBytes);
        var userMessage = UserMessage.builder()
                .text(KnowledgePrompts.buildImageDescriptionPrompt())
                .media(List.of(new Media(MimeTypeUtils.IMAGE_PNG, imageResource)))
                .build();
        var response = multimodalChatModel.call(new Prompt(List.of(userMessage)));
        String resp = response.getResult().getOutput().getText();
        log.info("PDF 图片识别完成，descriptionLength={}, descriptionSnippet={}",
                resp == null ? 0 : resp.length(),
                resp == null ? "" : truncateForLog(resp));
        return resp;
    }

    /**
     * 截断日志中的图片描述文本，避免输出过长。
     *
     * @param text 原始文本。
     * @return 截断后的日志文本。
     */
    private String truncateForLog(String text) {
        if (text == null || text.length() <= 500) {
            return text;
        }
        return text.substring(0, 500) + "...(truncated)";
    }

    /**
     * 构建写入 Spring AI Document 的文件元数据。
     *
     * @param file 原始 PDF 文件。
     * @return 包含路径、文件名、大小等信息的元数据。
     */
    private Map<String, Object> buildMetadata(File file) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", file.getAbsolutePath());
        metadata.put("fileName", file.getName());
        metadata.put("fileType", "pdf");
        metadata.put("fileSize", file.length());
        metadata.put("lastModified", file.lastModified());
        return metadata;
    }

    /**
     * 基于文件路径、大小和修改时间生成稳定的文档 id。
     *
     * @param file 原始 PDF 文件。
     * @return 文档唯一标识。
     * @throws IOException 生成摘要失败时抛出。
     */
    private String buildDocumentId(File file) throws IOException {
        String identity = file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashedBytes = digest.digest(identity.getBytes(StandardCharsets.UTF_8));
            return toHex(hashedBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("生成 PDF 文档 ID 失败: " + file.getName(), e);
        }
    }

    /**
     * 将字节数组转换为十六进制字符串。
     *
     * @param bytes 原始字节数组。
     * @return 十六进制字符串。
     */
    private String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            hex.append(Character.forDigit((current >> 4) & 0xF, 16));
            hex.append(Character.forDigit(current & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * 单个 PDF 处理结果，包含整理后的正文文本和抽取出的图片信息。
     */
    public record PdfProcessingResult(String content, List<ExtractedDocumentImage> extractedImages) {
    }

    // --- 内部数据模型 ---

    /**
     * 内容类型枚举
     * TEXT: 文本内容
     * IMAGE: 图片内容
     */
    private enum ContentType {TEXT, IMAGE}

    /**
     * 内容元素类，表示PDF中的一个文本或图片元素
     * 包含内容类型、实际内容和位置坐标信息
     */
    private static class ContentElement {
        /**
         * 内容类型（文本或图片）
         */
        private final ContentType type;
        /**
         * 内容的实际文本（对于图片，存储的是AI识别后的文本描述）
         */
        private final String content;
        /**
         * 左上角X坐标
         */
        private final int x0;
        /**
         * 左上角Y坐标（相对于页面底部的距离）
         */
        private final int y0;
        /**
         * 右下角X坐标（预留字段，当前未使用）
         */
        private final int x1;
        /**
         * 右下角Y坐标（预留字段，当前未使用）
         */
        private final int y1;

        /**
         * 创建 `ContentElement` 实例。
         *
         * @param type type 参数。
         * @param content content 参数。
         * @param x0 x0 参数。
         * @param y0 y0 参数。
         * @param x1 x1 参数。
         * @param y1 y1 参数。
         */
        public ContentElement(ContentType type, String content, int x0, int y0, int x1, int y1) {
            this.type = type;
            this.content = content;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }

        /**
         * 获取 `get Type` 对应结果。
         *
         * @return 返回处理结果。
         */
        public ContentType getType() {
            return type;
        }

        /**
         * 获取 `get Content` 对应结果。
         *
         * @return 返回处理结果。
         */
        public String getContent() {
            return content;
        }

        /**
         * 获取 `get X 0` 对应结果。
         *
         * @return 返回处理结果。
         */
        public int getX0() {
            return x0;
        }

        /**
         * 获取 `get Y 0` 对应结果。
         *
         * @return 返回处理结果。
         */
        public int getY0() {
            return y0;
        }
    }

    /**
     * 统一内容提取器
     * 继承PDFTextStripper，通过拦截PDF内容流操作符，实现文字和图片的统一坐标提取
     * <p>
     * 工作原理：
     * 1. 继承PDFTextStripper以获取文本提取能力
     * 2. 重写writeString方法来捕获文本及其位置
     * 3. 重写processOperator方法来拦截图片绘制指令
     * 4. 将文字和图片统一记录到elements列表中，保留其坐标信息
     * 5. 所有元素使用统一的坐标系（相对于页面底部的距离），便于后续排序
     */
    private class UnifiedContentStripper extends PDFTextStripper {
        /**
         * 存储提取到的所有内容元素（文字和图片）
         */
        private final List<ContentElement> elements = new ArrayList<>();
        /**
         * 页面高度，用于坐标系转换
         */
        private final float pageHeight;

        private final int pageNumber;

        private final AtomicInteger imageIndexCounter;

        private final List<ExtractedDocumentImage> extractedImages;

        /**
         * 创建 `UnifiedContentStripper` 实例。
         *
         * @param pageHeight pageHeight 参数。
         * @param pageNumber pageNumber 参数。
         * @param imageIndexCounter imageIndexCounter 参数。
         * @param extractedImages extractedImages 参数。
         * @throws IOException 异常信息。
         */
        public UnifiedContentStripper(float pageHeight,
                                      int pageNumber,
                                      AtomicInteger imageIndexCounter,
                                      List<ExtractedDocumentImage> extractedImages) throws IOException {
            super();
            this.pageHeight = pageHeight;
            this.pageNumber = pageNumber;
            this.imageIndexCounter = imageIndexCounter;
            this.extractedImages = extractedImages;
            // 注册图片绘制相关操作符的拦截器，以便捕获图片的绘制指令
            addOperator(new DrawObject(this));        // Do操作符：绘制XObject（包括图片）
            addOperator(new SetMatrix(this));          // cm操作符：设置当前变换矩阵
            addOperator(new Concatenate(this));        // 矩阵连接操作
            addOperator(new SetGraphicsStateParameters(this)); // gs操作符：设置图形状态参数
        }

        /**
         * 处理 `write String` 对应逻辑。
         *
         * @param text text 参数。
         * @param textPositions textPositions 参数。
         */
        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            // 只处理非空的文本和位置信息
            if (!textPositions.isEmpty() && !text.trim().isEmpty()) {
                // 获取文本块第一个字符的位置
                TextPosition first = textPositions.get(0);
                // 获取X坐标（已经是正确的从左到右）
                int x0 = (int) first.getXDirAdj();
                // 将Y坐标从"从上到下"转换为"相对于底部的距离"，以便与图片坐标统一
                int y0 = (int) (pageHeight - first.getYDirAdj());
                // 创建文本元素并添加到列表中
                elements.add(new ContentElement(ContentType.TEXT, text.trim(), x0, y0, 0, 0));
            }
        }

        /**
         * 处理 `process Operator` 对应逻辑。
         *
         * @param operator operator 参数。
         * @param operands operands 参数。
         * @throws IOException 异常信息。
         */
        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            String operation = operator.getName();
            // 拦截"Do"指令（PDF中用于绘制XObject的指令，包括图片）
            if ("Do".equals(operation)) {
                // 获取XObject的名称
                COSName objectName = (COSName) operands.get(0);
                // 通过名称获取XObject对象
                PDXObject xobject = getResources().getXObject(objectName);

                // 判断XObject是否为图片对象
                if (xobject instanceof PDImageXObject image) {
                    // 获取当前图形状态中的变换矩阵（CTM），包含图片的位置和尺寸信息
                    Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();

                    // CTM使用底向上的坐标系
                    // 获取图片左下角的X坐标
                    float x = ctm.getTranslateX();
                    // 获取图片左下角的Y坐标
                    float y = ctm.getTranslateY();
                    // 获取图片的高度（Y方向的缩放因子）
                    float h = ctm.getScalingFactorY();

                    // 为了与文本坐标系统一，y0记为图片上沿距底部的距离
                    // 图片上沿 = 左下角Y坐标 + 高度
                    int x0 = (int) x;
                    int y0 = (int) (y + h);

                    // 实时处理图片：提取、转换、AI识别
                    int imageIndex = imageIndexCounter.getAndIncrement();
                    ExtractedDocumentImage processedImage = processImage(image, pageNumber, imageIndex);
                    extractedImages.add(processedImage);
                    elements.add(new ContentElement(ContentType.IMAGE, KnowledgeChunkConstant.IMAGE_PLACEHOLDER_PREFIX + imageIndex + KnowledgeChunkConstant.IMAGE_PLACEHOLDER_SUFFIX, x0, y0, 0, 0));
                }
            } else {
                // 对于其他操作符，调用父类的默认处理
                super.processOperator(operator, operands);
            }
        }

        /**
         * 获取 `get Elements` 对应结果。
         *
         * @return 返回处理结果。
         */
        public List<ContentElement> getElements() {
            return elements;
        }
    }
}
