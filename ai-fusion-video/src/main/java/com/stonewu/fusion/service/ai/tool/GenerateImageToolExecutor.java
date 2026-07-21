package com.stonewu.fusion.service.ai.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.generation.ImageItem;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.ImageGenerationService;
import com.stonewu.fusion.service.generation.consumer.ImageGenerationConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 生图工具（generate_image）
 * <p>
 * 职责：解析参数 → 构建 ImageTask → 提交到队列并同步等待结果。
 * <p>
 * 排队、并发控制、策略路由等全部由 {@link ImageGenerationConsumer} 统一处理，
 * 本工具通过 {@link ImageGenerationConsumer#submitAndWait} 复用其完整流程。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GenerateImageToolExecutor implements ToolExecutor {

    /** 模型类型常量：图片生成 */
    private static final int MODEL_TYPE_IMAGE = 2;

    /** 同步等待超时时间（30 分钟） */
    private static final long WAIT_TIMEOUT_MS = 30 * 60 * 1000L;

    /** 可恢复失败的最大尝试次数 */
    private static final int MAX_IMAGE_GENERATION_ATTEMPTS = 3;

    private final AiModelService aiModelService;
    private final ImageGenerationService imageGenerationService;
    private final ImageGenerationConsumer imageGenerationConsumer;
    private final GenerationModelCapabilityService generationModelCapabilityService;

    @Override
    public String getToolName() {
        return "generate_image";
    }

    @Override
    public String getDisplayName() {
        return "AI 生成图片";
    }

    @Override
    public String getToolDescription() {
        return """
                生成AI图片。此工具仅负责生图，不会自动保存到资产库。

                适用场景：
                1. 为角色生成立绘：根据角色的外貌、性格描述生成设定图
                2. 为分镜生成画面：根据分镜的场景、内容描述生成画面图
                3. 生成场景图、道具图等创意素材

                重要提示：
                - 生成完成后，如需将图片保存到角色/场景/道具等资产中，请使用 update_asset_image 工具
                - 提示词要详细具体，包含画面的主体、风格、视角等信息
                - 可以使用中文提示词，系统会自动处理
                - 如果你打算传 imageUrls，或不确定当前默认模型是否支持参考图，请先调用 get_generation_model_capabilities
                
                %s
                """.formatted(describeCurrentModelCapability());
    }

    @Override
    public String getParametersSchema() {
            AiModel model = resolvePreferredModelOrNull();
            GenerationModelCapabilityService.ImageModelCapability capability = model != null
                ? generationModelCapabilityService.resolveImageCapability(model)
                : null;
            String imageUrlDescription = capability != null && !capability.supportsReferenceImages()
                ? "当前默认模型不支持参考图，请不要传该字段"
                : "参考图片 URL 列表（用于图生图，文生图时不传）";

            return JSONUtil.createObj()
                .set("type", "object")
                .set("properties", JSONUtil.createObj()
                    .set("prompt", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "图片生成提示词（英文效果更佳）"))
                    .set("negativePrompt", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "反向提示词，描述不希望出现的内容"))
                    .set("width", JSONUtil.createObj()
                        .set("type", "number")
                        .set("description", "图片宽度（默认使用模型配置中的默认宽度）"))
                    .set("height", JSONUtil.createObj()
                        .set("type", "number")
                        .set("description", "图片高度（默认使用模型配置中的默认高度）"))
                    .set("style", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "风格（如 realistic, anime, watercolor 等）"))
                    .set("imageUrls", JSONUtil.createObj()
                        .set("type", "array")
                        .set("items", JSONUtil.createObj().set("type", "string"))
                        .set("description", imageUrlDescription)))
                .set("required", JSONUtil.parseArray("[\"prompt\"]"))
                .toString();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            String prompt = params.getStr("prompt");
            if (StrUtil.isBlank(prompt)) {
                return errorResult("缺少 prompt");
            }

            int width = params.getInt("width", 0);
            int height = params.getInt("height", 0);

            // 参考图片（图生图）
            String refImageUrls = null;
            if (params.containsKey("imageUrls")) {
                List<String> imageUrls = params.getJSONArray("imageUrls").toList(String.class);
                if (!imageUrls.isEmpty()) {
                    refImageUrls = JSONUtil.toJsonStr(imageUrls);
                }
            }

            AiModel model = resolvePreferredModel();

            ImageTask validationTask = buildImageTask(prompt, width, height, refImageUrls, model, context);

            generationModelCapabilityService.validateImageTask(model, validationTask);

            Exception lastFailure = null;
            int attemptsUsed = 0;
            for (int attempt = 0; attempt < MAX_IMAGE_GENERATION_ATTEMPTS; attempt++) {
                ImageTask task = buildImageTask(prompt, width, height, refImageUrls, model, context);
                int attemptNumber = attempt + 1;
                attemptsUsed = attemptNumber;

                try {
                    log.info("[generate_image] 提交生图任务: attempt={}/{}, prompt={}, size={}x{}, modelId={}, modelCode={}, 参考图: {}",
                            attemptNumber, MAX_IMAGE_GENERATION_ATTEMPTS, prompt, width, height, model.getId(), model.getCode(),
                            refImageUrls != null ? "有" : "无");

                    // 提交到队列并同步等待结果
                    ImageTask completed = imageGenerationConsumer.submitAndWait(task, WAIT_TIMEOUT_MS);

                    // 从完成的任务中获取生成的图片 URL
                    List<ImageItem> items = imageGenerationService.listItems(completed.getId());
                    String imageUrl = items.stream()
                            .filter(item -> StrUtil.isNotBlank(item.getImageUrl()))
                            .map(ImageItem::getImageUrl)
                            .findFirst()
                            .orElse(null);

                    if (imageUrl == null) {
                        throw new IllegalStateException("生成完成但未获取到图片 URL");
                    }

                    log.info("[generate_image] 生成成功: attempt={}/{}, url={}",
                            attemptNumber, MAX_IMAGE_GENERATION_ATTEMPTS, imageUrl);

                    return JSONUtil.createObj()
                            .set("status", "success")
                            .set("imageUrl", imageUrl)
                            .set("prompt", prompt)
                            .set("attempts", attemptNumber)
                            .toString();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return errorResult("生成任务被中断");
                } catch (Exception e) {
                    lastFailure = e;
                    if (isNonRetryableFailure(e)) {
                        log.warn("[generate_image] 生图失败，不再重试: attempt={}/{}, errorType={}, error={}",
                                attemptNumber, MAX_IMAGE_GENERATION_ATTEMPTS, e.getClass().getSimpleName(), e.getMessage());
                        break;
                    }
                    if (attempt + 1 < MAX_IMAGE_GENERATION_ATTEMPTS) {
                        log.warn("[generate_image] 生图失败，准备自动重试: attempt={}/{}, nextAttempt={}, errorType={}, error={}",
                                attemptNumber, MAX_IMAGE_GENERATION_ATTEMPTS, attemptNumber + 1, e.getClass().getSimpleName(), e.getMessage());
                        try {
                            sleepBeforeRetry(attemptNumber);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return errorResult("生成任务被中断");
                        }
                        continue;
                    }
                    log.error("[generate_image] 生成图片失败，已尝试{}次", MAX_IMAGE_GENERATION_ATTEMPTS, e);
                }
            }

            return errorResult("生成失败，已尝试" + attemptsUsed + "次: "
                    + (lastFailure != null ? lastFailure.getMessage() : "未知错误"));
        } catch (Exception e) {
            log.error("[generate_image] 生成图片失败", e);
            return errorResult("生成失败: " + e.getMessage());
        }
    }

    private boolean isNonRetryableFailure(Exception e) {
        String message = e.getMessage();
        if (StrUtil.isBlank(message)) {
            return false;
        }
        String lowerMessage = message.toLowerCase();
        return message.contains("本地参考图不存在")
                || message.contains("参考图不存在")
                || message.contains("不支持参考图")
                || lowerMessage.contains("http 400")
                || lowerMessage.contains("http 401")
                || lowerMessage.contains("http 403")
                || lowerMessage.contains("authorization failed")
                || lowerMessage.contains("invalid api key");
    }

    private void sleepBeforeRetry(int attemptNumber) throws InterruptedException {
        long delayMs = attemptNumber == 1 ? 500L : 1500L;
        Thread.sleep(delayMs);
    }

    /**
     * 获取默认图片生成模型的 ID
     */
    private AiModel resolvePreferredModel() {
        AiModel defaultModel = aiModelService.getDefaultByType(MODEL_TYPE_IMAGE);
        if (defaultModel != null) {
            return defaultModel;
        }
        List<AiModel> imageModels = aiModelService.getListByType(MODEL_TYPE_IMAGE);
        if (!imageModels.isEmpty()) {
            return imageModels.get(0);
        }
        throw new IllegalStateException("未配置可用的图片生成模型");
    }

    private AiModel resolvePreferredModelOrNull() {
        try {
            return resolvePreferredModel();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String describeCurrentModelCapability() {
        AiModel model = resolvePreferredModelOrNull();
        return generationModelCapabilityService.describeImageCapability(model);
    }

    private ImageTask buildImageTask(String prompt, int width, int height, String refImageUrls,
                                     AiModel model, ToolExecutionContext context) {
        return ImageTask.builder()
                .prompt(prompt)
                .width(width > 0 ? width : null)
                .height(height > 0 ? height : null)
                .refImageUrls(refImageUrls)
                .modelId(model.getId())
                .count(1)
                .userId(context.getUserId())
                .build();
    }

    private String errorResult(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }
}
