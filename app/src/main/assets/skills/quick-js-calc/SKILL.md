---
name: quick-js-calc
description: Evaluates unit conversions, tip splits, and arithmetic inside an isolated headless WebView sandbox via window.ai_edge_gallery_get_result.
metadata:
  tool: run_js
---

# Quick JS Sandbox Calculator Skill

Use `run_js` when the user asks for arithmetic calculations, currency/unit conversions, or bill splitting on their wrist. Pass a pure JavaScript expression; external network calls (`fetch`, `XMLHttpRequest`) are blocked by PromptInjectionShield Chokepoint B.
