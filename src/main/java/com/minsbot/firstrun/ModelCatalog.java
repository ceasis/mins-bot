package com.minsbot.firstrun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Curated catalog of local models grouped by category. Shown in the Models
 * browser page so the user can pick from a sensible shortlist instead of the
 * full Ollama / HuggingFace firehose.
 *
 * <p>Two backends:
 * <ul>
 *   <li>{@code ollama} — pulled via {@code POST /api/pull} on {@code localhost:11434}.</li>
 *   <li>{@code comfyui} — image-generation checkpoints that live in
 *       {@code ComfyUI/models/checkpoints/} or {@code ComfyUI/models/unet/}.
 *       Install flow surfaces a setup guide when ComfyUI is not running.</li>
 * </ul>
 */
final class ModelCatalog {

    private ModelCatalog() {}

    static Map<String, Object> curated() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", List.of(
                category("llm", "llm", "General LLMs", "Chat, reasoning, writing. Pick one as your daily driver.", List.of(
                        ollama("llama3.2:1b", "Llama 3.2 1B", "Tiny & fast. 4–8 GB RAM. Good for quick replies on weak hardware.", "1.3 GB", 1300, 4,
                                "Fastest in the 1–2 B class. Weaker reasoning than phi3:3.8b. Pick only if RAM is very tight."),
                        ollama("llama3.2:3b", "Llama 3.2 3B", "Default. Balanced quality/speed. Works on most laptops.", "2.0 GB", 2000, 8,
                                "Best sub-4 GB default. Smarter than phi3:3.8b; smaller & quicker than mistral:7b."),
                        ollama("llama3.1:8b", "Llama 3.1 8B", "Smarter. Needs ~12 GB RAM. Slower on CPU.", "4.7 GB", 4700, 12,
                                "Closer to GPT-3.5 quality. Slightly smarter than mistral:7b on long prompts; slower."),
                        ollama("mistral:7b", "Mistral 7B", "Strong all-rounder from Mistral. Good instruction following.", "4.1 GB", 4100, 10,
                                "Smoother writing than llama3.1:8b; weaker on math. Well-tested baseline for 7 B class."),
                        ollama("qwen2.5:7b", "Qwen 2.5 7B", "Multilingual. Strong in code & reasoning.", "4.4 GB", 4400, 10,
                                "Best multilingual 7 B. Matches mistral:7b for English; beats it on CJK / Arabic / code."),
                        ollama("gemma2:9b", "Gemma 2 9B", "Google's open model. Careful, polished tone.", "5.4 GB", 5400, 12,
                                "Safer, more polished tone than llama3.1:8b. Similar quality; more conservative refusals."),
                        ollama("phi3:3.8b", "Phi-3 Mini", "Microsoft's small model. Punches above its weight.", "2.2 GB", 2200, 8,
                                "Best reasoning in sub-4 GB class. Weaker at open-ended creative writing than llama3.2:3b."),
                        ollama("deepseek-r1:7b", "DeepSeek-R1 7B", "Reasoning-tuned. Thinks before answering. Slower.", "4.7 GB", 4700, 12,
                                "Chain-of-thought style like o1-mini. Stronger math/logic than mistral:7b; slower replies.")
                )),
                category("vision", "vision", "Vision LLMs", "Understand images — screenshots, photos, diagrams.", List.of(
                        ollama("moondream", "Moondream 1.8B", "Tiny vision model. Fast. Good for basic screenshot Q&A.", "1.7 GB", 1700, 6,
                                "Fastest vision model (~3× quicker than llava:7b). Less detail, weaker OCR."),
                        ollama("llava:7b", "LLaVA 7B", "Popular vision LLM. Broad visual knowledge.", "4.5 GB", 4500, 10,
                                "Classic 7 B baseline. Broader than moondream; OCR weaker than llama3.2-vision:11b."),
                        ollama("llava:13b", "LLaVA 13B", "Bigger LLaVA. Better detail understanding.", "8.0 GB", 8000, 16,
                                "~2× slower than llava:7b for noticeably better fine-detail understanding."),
                        ollama("llama3.2-vision:11b", "Llama 3.2 Vision 11B", "Meta's multimodal Llama. Strong OCR & charts.", "7.9 GB", 7900, 14,
                                "Best OCR & chart reading in this list. Heavier than llava:7b; better image-reasoning."),
                        ollama("bakllava", "BakLLaVA 7B", "Mistral-based vision variant. Alternative to LLaVA.", "4.4 GB", 4400, 10,
                                "Mistral-backed swap for llava:7b. Comparable quality; different writing style.")
                )),
                category("code", "code", "Code models", "Tuned for writing, explaining, and reviewing code.", List.of(
                        ollama("qwen2.5-coder:1.5b", "Qwen2.5-Coder 1.5B", "Tiny code assistant. Fast autocomplete-style help.", "1.0 GB", 1000, 4,
                                "Fastest coder in this list. Short context; weaker refactors than qwen2.5-coder:7b."),
                        ollama("qwen2.5-coder:7b", "Qwen2.5-Coder 7B", "Recommended code model in 2025. Strong refactoring.", "4.4 GB", 4400, 10,
                                "Strongest small code model today. Beats codellama:7b on modern frameworks & refactors."),
                        ollama("codellama:7b", "Code Llama 7B", "Meta's classic code model. Stable & well-tested.", "3.8 GB", 3800, 10,
                                "Older but stable. Weaker than qwen2.5-coder:7b on 2024+ stacks (Next, Svelte, FastAPI)."),
                        ollama("deepseek-coder-v2:16b", "DeepSeek-Coder V2 16B", "High-end code model. Needs a strong machine.", "9.0 GB", 9000, 20,
                                "Best-in-class open coder. ~2× smarter than qwen2.5-coder:7b; 2× the hardware bill.")
                )),
                category("embed", "embed", "Embeddings", "For local RAG / semantic search. Not for chat.", List.of(
                        ollama("nomic-embed-text", "Nomic Embed Text", "Default embedder. 768-dim. Fast.", "274 MB", 274, 2,
                                "Fast default. Slightly lower retrieval recall than mxbai-embed-large."),
                        ollama("mxbai-embed-large", "mxbai-embed-large", "Larger embedder. Higher recall for retrieval.", "669 MB", 669, 4,
                                "Higher MTEB scores than nomic. ~2–3× slower embed; worth it for serious RAG.")
                )),
                category("image_gen", "image", "Image generation (local)", "Runs via ComfyUI on localhost:8188. GPU strongly recommended.", List.of(
                        comfyui("sdxl-lightning-4step", "SDXL Lightning 4-step",
                                "Fast SDXL. 2–4 sec per image on 8 GB GPU. Good everyday quality.",
                                "6.9 GB", 6900, 6,
                                "checkpoints", "sdxl_lightning_4step.safetensors",
                                "https://huggingface.co/ByteDance/SDXL-Lightning/resolve/main/sdxl_lightning_4step.safetensors",
                                "Fastest SDXL variant. Slightly lower detail than full SDXL 30-step but 6–8× quicker."),
                        comfyui("juggernaut-xl-lightning", "Juggernaut XL Lightning",
                                "Photorealistic SDXL + Lightning LoRA baked in. Great for realistic photos.",
                                "6.6 GB", 6600, 6,
                                "checkpoints", "juggernautXL_v9Rdphoto2Lightning.safetensors",
                                "https://huggingface.co/RunDiffusion/Juggernaut-XL-Lightning/resolve/main/juggernautXL_v9Rdphoto2Lightning.safetensors",
                                "Best photorealism in this list. Sharper faces & skin than base SDXL-Lightning."),
                        comfyui("flux1-schnell-nf4", "FLUX.1 schnell (NF4)",
                                "Premium FLUX quality, 4-bit quantized to fit 8 GB. ~30 sec per image.",
                                "6.8 GB", 6800, 8,
                                "checkpoints", "flux1-schnell-bnb-nf4.safetensors",
                                "https://huggingface.co/silveroxides/flux1-schnell-nf4/resolve/main/flux1-schnell-bnb-nf4.safetensors",
                                "Near-cloud-tier quality. Beats SDXL on prompt adherence, complex scenes, hands. Slower."),
                        comfyui("sd15-realistic-vision", "SD 1.5 Realistic Vision v6",
                                "Tiny 4 GB model. Fast on low VRAM. Decent photorealism for quick iteration.",
                                "2.1 GB", 2100, 4,
                                "checkpoints", "realisticVisionV60B1_v51HyperVAE.safetensors",
                                "https://huggingface.co/SG161222/Realistic_Vision_V6.0_B1_noVAE/resolve/main/Realistic_Vision_V6.0_B1_fp16.safetensors",
                                "Older SD 1.5 but very fast on 4–6 GB cards. Weaker than SDXL on wide scenes / text.")
                ))
        ));
        out.put("comingSoon", List.of(
                Map.of("name", "Whisper (speech-to-text)", "note", "Runs via whisper.cpp, not Ollama. Phase 2."),
                Map.of("name", "Piper / Coqui (text-to-speech)", "note", "Runs as its own binary. Phase 2."),
                Map.of("name", "FLUX.1 dev (NF4)", "note", "Higher quality than schnell but ~2× slower. Phase 2."),
                Map.of("name", "Stable Diffusion 3.5 Medium", "note", "Tight on 8 GB — needs offload profile tuning. Phase 2.")
        ));
        return out;
    }

    private static Map<String, Object> category(String id, String kind, String label, String desc, List<Map<String, Object>> models) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("kind", kind);
        c.put("label", label);
        c.put("description", desc);
        c.put("models", new ArrayList<>(models));
        return c;
    }

    /** Ollama-backed model (LLM / vision / code / embed). Runs on CPU; GPU optional for speed. */
    private static Map<String, Object> ollama(String tag, String name, String description,
                                              String sizeLabel, long sizeMb, int minRamGb, String vs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", tag);
        m.put("name", name);
        m.put("description", description);
        m.put("sizeLabel", sizeLabel);
        m.put("sizeMb", sizeMb);
        m.put("vs", vs);
        m.put("backend", "ollama");
        m.put("requiresGpu", false);
        m.put("minVramGb", 0);
        m.put("minRamGb", minRamGb);
        return m;
    }

    /** ComfyUI-backed image model. Requires GPU. {@code folder} is relative to ComfyUI/models/. */
    private static Map<String, Object> comfyui(String tag, String name, String description,
                                               String sizeLabel, long sizeMb, int minVramGb,
                                               String folder, String filename, String downloadUrl,
                                               String vs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", tag);
        m.put("name", name);
        m.put("description", description);
        m.put("sizeLabel", sizeLabel);
        m.put("sizeMb", sizeMb);
        m.put("vs", vs);
        m.put("backend", "comfyui");
        m.put("requiresGpu", true);
        m.put("minVramGb", minVramGb);
        m.put("minRamGb", 0);
        m.put("comfyFolder", folder);
        m.put("comfyFilename", filename);
        m.put("comfyDownloadUrl", downloadUrl);
        return m;
    }
}
