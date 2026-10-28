# MOSS-Image 文生图

基于 MOSS-Image SFT 的 ComfyUI 文生图插件。适配器把官方 `Text to Image (MOSS-Image)` 子图展开为可执行的 API 节点链。工作流默认启用提示词扩写：将原始提示词改写为完整场景 caption，并通过 `PreviewAny` 展示实际送入图像编码器的文本。扩写和图像条件共用一个 `qwen3.5_9b_w4a8_gptq.safetensors`，加载类型为 `moss_image`。

## 模型

把模型放入 ComfyUI 对应目录：

```text
ComfyUI/models/
├── diffusion_models/
│   ├── moss_image_sft_int8_convrot.safetensors
│   ├── moss_image_sft_bf16.safetensors
│   ├── moss_image_base_int8_convrot.safetensors  # optional Base variant
│   └── moss_image_base_bf16.safetensors          # optional Base variant
├── text_encoders/
│   └── qwen3.5_9b_w4a8_gptq.safetensors
└── vae/
    └── flux2-vae_small.safetensors
```

- [MOSS-Image 模型仓库](https://huggingface.co/Comfy-Org/MOSS-Image)
- [INT8 diffusion model](https://huggingface.co/Comfy-Org/MOSS-Image/resolve/main/diffusion_models/moss_image_sft_int8_convrot.safetensors)
- [BF16 diffusion model](https://huggingface.co/Comfy-Org/MOSS-Image/resolve/main/diffusion_models/moss_image_sft_bf16.safetensors)
- [Base INT8 variant](https://huggingface.co/Comfy-Org/MOSS-Image/resolve/main/diffusion_models/moss_image_base_int8_convrot.safetensors)
- [Base BF16 variant](https://huggingface.co/Comfy-Org/MOSS-Image/resolve/main/diffusion_models/moss_image_base_bf16.safetensors)
- [Qwen 3.5 text encoder](https://huggingface.co/Comfy-Org/MOSS-Image/resolve/main/text_encoders/qwen3.5_9b_w4a8_gptq.safetensors)
- [FLUX.2 small VAE](https://huggingface.co/Comfy-Org/MOSS-Image/resolve/main/vae/flux2-vae_small.safetensors)

`moss_image_sft_*` 是本插件默认使用的指令微调检查点；`moss_image_base_*` 也可以在同一工作流中选择。SFT 的文本分组和布局更清晰；Base 未经后训练和 CFG 蒸馏，适合尝试社区 LoRA。INT8 是默认选项，BF16 可在模型选项中切换。

## 提示词扩写

`enable_prompt_upsampler` 默认开启。扩写器使用 MOSS-Image 自带的 Qwen 编码器；关闭后将原始提示词直接发送给图像模型。扩写器的 system prompt 参考 [MOSS-Image Prompt Enhancer skill](https://github.com/OpenMOSS/MOSS-Image/blob/main/skills/moss-image-prompt-enhancer/SKILL.md)，原始 MOSS-Image 模板的节点结构见 [ComfyUI 子图指南](https://docs.comfy.org/interface/features/subgraph)。最终 caption 会通过 `PreviewAny` 预览。

## 默认设置

| 参数 | 默认值 |
| --- | --- |
| 尺寸 | 1024×1024（宽高为 16 的倍数） |
| Steps | 25 |
| CFG | 2.5 |
| Sampler / scheduler | `er_sde` / `simple` |
| Denoise | 1 |
| Shift | 5（模型默认值） |
| Negative prompt | `nsfw, worst quality, bad anatomy, watermark, jpeg artifact` |
| Skip Layer Guidance | 关闭；内部保留 layer 22 设置 |

这些采样值侧重较快出图；更接近 OpenMOSS 官方设置时可尝试 50 steps、CFG 3 和 Euler。开启 SLG 可能改善细节，也可能让动漫风格过强；可在工作流中打开并调整 layer 22。显存紧张时降低分辨率，或使用 Sequential / CPU offload。训练 LoRA 时冻结文本编码器。

MOSS-Image 推荐约 1M、1.5M 或 2M 像素，宽高必须为正数且为 16 的倍数；外推范围约为 0.25M 至 4M 像素。支持自然语言、标签和实验性的 JSON Layout，也支持中英文文字排版；英文描述通常更贴近其训练数据。自动换行可能不符合预期，精确换行可在提示词中使用 `\n` 或实际换行符。模型采用 Apache 2.0 许可。

模型约为 5B 参数，支持较长提示词（约 4K tokens）、动漫和写实图像；文字排版是其强项之一。社区反馈 SFT 有时会出现色带，实验性 LoRA 可尝试约 -0.5 强度；SLG 在部分动漫风格上也可能显得过强。

## ComfyUI 版本与问题报告

此工作流使用 ComfyUI 原生 MOSS-Image 节点。需使用包含 MOSS-Image 支持的 ComfyUI master/nightly（官方支持自提交 `e087d8f6` 起）；稳定版尚未包含支持时，先按[更新指南](https://docs.comfy.org/installation/update_comfyui)更新 ComfyUI 并准备好上述模型。

- ComfyUI 运行错误：[ComfyUI/issues](https://github.com/comfyanonymous/ComfyUI/issues)
- 界面 / 前端问题：[ComfyUI_frontend/issues](https://github.com/Comfy-Org/ComfyUI_frontend/issues)
- 工作流问题：[workflow_templates/issues](https://github.com/Comfy-Org/workflow_templates/issues)
