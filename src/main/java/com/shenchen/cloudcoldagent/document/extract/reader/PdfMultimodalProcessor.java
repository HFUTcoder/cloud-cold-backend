package com.shenchen.cloudcoldagent.document.extract.reader;

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
import java.util.Base64;
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

    public PdfMultimodalProcessor(PdfMultimodalProperties pdfMultimodalProperties) {
        this.pdfMultimodalProperties = pdfMultimodalProperties;
    }

    @Override
    public boolean supports(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".pdf");
    }

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
     * 处理PDF文件，提取其中的文字和图片内容
     *
     * @param pdfFile PDF文件对象
     * @return 按照阅读顺序整合后的文本内容（包含图片的文本描述）
     * @throws Exception 文件读取或处理过程中的异常
     *                   <p>
     *                   处理流程：
     *                   1. 加载PDF文档
     *                   2. 逐页遍历处理
     *                   3. 对每一页，使用自定义的UnifiedContentStripper同时提取文字和图片
     *                   4. 按照坐标位置对提取的内容进行排序
     *                   5. 按顺序拼接所有内容
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

                // 按照排序后的顺序，将所有内容元素拼接到最终文本中
                for (ContentElement element : allElements) {
                    if (element.getType() == ContentType.TEXT) {
                        finalText.append(element.getContent()).append("\n");
                    }
                }
                // 每页处理完后添加一个空行分隔
                finalText.append("\n");
            }
            log.info("PDF处理完成");
            // 返回去除首尾空白的最终文本
            return new PdfProcessingResult(finalText.toString().trim(), extractedImages);
        }
    }

    /**
     * 处理单张图片，将图片转换为Base64编码并调用AI进行图片识别
     *
     * @param image PDFBox的图片对象
     * @return 图片的文本描述（通过AI识别得到）
     *
     * 处理流程：
     * 1. 将PDImageXObject转换为BufferedImage
     * 2. 将BufferedImage编码为PNG格式的字节数组
     * 3. 将字节数组转换为Base64字符串
     * 4. 调用AI接口进行图片识别，获取文本描述
     * 5. 如果处理失败，返回错误提示
     */
    /**
     * 处理单张图片：上传 MinIO + AI 识别 + 返回 <image> 标签
     */
    private ExtractedDocumentImage processImage(PDImageXObject image, int pageNumber, int imageIndex) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BufferedImage bufferedImage = image.getImage();
            ImageIO.write(bufferedImage, "png", baos);
            byte[] imageBytes = baos.toByteArray();
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String description = image2Text(base64Image);
            description = (description == null || description.trim().isEmpty()) ? "[无法识别图片内容]" : description.trim();
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
                    "[图片处理错误]"
            );
        }
    }

    /**
     * 转义XML特殊字符，防止XML解析错误
     *
     * @param text 需要转义的文本
     * @return 转义后的文本
     * <p>
     * 转义规则：
     * & -> &amp;
     * < -> &lt;
     * > -> &gt;
     * " -> &quot;
     * ' -> &apos;
     */
    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private OpenAiChatModel multimodalChatModel;

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
     * 将图片转换为文本描述（使用AI识别）
     *
     * @param imageBase64 图片的Base64编码字符串
     * @return 图片的文本描述
     */
    public String image2Text(String imageBase64) {
        byte[] imageBytes = Base64.getDecoder().decode(imageBase64);
        ByteArrayResource imageResource = new ByteArrayResource(imageBytes);
        var userMessage = UserMessage.builder()
                .text("请描述这张图片的内容，包括场景、对象、布局、颜色、文字信息，直接输出纯文本描述，不要多余说明。")
                .media(List.of(new Media(MimeTypeUtils.IMAGE_PNG, imageResource)))
                .build();
        var response = multimodalChatModel.call(new Prompt(List.of(userMessage)));
        String resp = response.getResult().getOutput().getText();
        log.info("PDF 图片识别完成，descriptionLength={}, descriptionSnippet={}",
                resp == null ? 0 : resp.length(),
                resp == null ? "" : truncateForLog(resp));
        return resp;
    }

    private String truncateForLog(String text) {
        if (text == null || text.length() <= 500) {
            return text;
        }
        return text.substring(0, 500) + "...(truncated)";
    }

    private Map<String, Object> buildMetadata(File file) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", file.getAbsolutePath());
        metadata.put("fileName", file.getName());
        metadata.put("fileType", "pdf");
        metadata.put("fileSize", file.length());
        metadata.put("lastModified", file.lastModified());
        return metadata;
    }

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

    private String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            hex.append(Character.forDigit((current >> 4) & 0xF, 16));
            hex.append(Character.forDigit(current & 0xF, 16));
        }
        return hex.toString();
    }

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
         * 构造函数
         *
         * @param type    内容类型
         * @param content 内容文本
         * @param x0      左上角X坐标
         * @param y0      左上角Y坐标（相对于页面底部）
         * @param x1      右下角X坐标
         * @param y1      右下角Y坐标
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
         * 获取内容类型
         *
         * @return 内容类型（TEXT或IMAGE）
         */
        public ContentType getType() {
            return type;
        }

        /**
         * 获取内容文本
         *
         * @return 对于文本元素返回原文本，对于图片元素返回AI识别后的描述
         */
        public String getContent() {
            return content;
        }

        /**
         * 获取左上角X坐标
         *
         * @return X坐标值
         */
        public int getX0() {
            return x0;
        }

        /**
         * 获取左上角Y坐标（相对于页面底部的距离）
         *
         * @return Y坐标值
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
         * 构造函数
         *
         * @param pageHeight 页面高度，用于将PDF坐标系（原点在左下）转换为阅读坐标系（原点在左上）
         * @throws IOException 初始化异常
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
         * 重写writeString方法，捕获文本内容及其位置信息
         * 此方法在PDFTextStripper提取到文本时被调用
         *
         * @param text          提取到的文本内容
         * @param textPositions 文本中每个字符的位置信息列表
         *                      <p>
         *                      处理逻辑：
         *                      1. 检查文本和位置信息是否有效（非空且非纯空白）
         *                      2. 获取第一个字符的位置作为该文本块的位置
         *                      3. 将PDF坐标系（原点在左下）转换为统一坐标系（相对于底部的距离）
         *                      4. 创建TEXT类型的ContentElement并添加到elements列表
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
         * 重写processOperator方法，拦截PDF内容流操作符
         * 主要用于捕获图片绘制指令（Do操作符）
         *
         * @param operator PDF操作符对象
         * @param operands 操作符的操作数列表
         * @throws IOException 处理异常
         *                     <p>
         *                     PDF中的Do操作符用于绘制XObject，包括图片、表单等
         *                     我们拦截此操作符来获取图片对象及其位置信息
         *                     <p>
         *                     坐标系说明：
         *                     - PDF使用CTM（当前变换矩阵）来定位和缩放对象
         *                     - CTM的坐标系原点在左下角，Y轴向上为正
         *                     - getTranslateX()获取图片左下角的X坐标
         *                     - getTranslateY()获取图片左下角的Y坐标
         *                     - getScalingFactorY()获取图片的高度
         *                     - 图片上沿Y坐标 = 左下角Y坐标 + 高度
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
                    elements.add(new ContentElement(ContentType.IMAGE, "[IMAGE_" + imageIndex + "]", x0, y0, 0, 0));
                }
            } else {
                // 对于其他操作符，调用父类的默认处理
                super.processOperator(operator, operands);
            }
        }

        /**
         * 获取提取到的所有内容元素列表
         *
         * @return 包含文字和图片的内容元素列表
         */
        public List<ContentElement> getElements() {
            return elements;
        }
    }
}
