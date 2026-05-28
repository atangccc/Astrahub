import { defineConfig, presetIcons } from "unocss";

export default defineConfig({
  presets: [
    presetIcons({
      scale: 1.2,
      extraProperties: {
        display: "inline-block",
        "vertical-align": "middle",
      },
    }),
  ],
});
