# Third-party reading background textures

SimpleReader v585 uses resized and grayscale-derived texture maps from ambientCG:

- Paper 001: https://ambientcg.com/view?id=Paper001
- Paper 006: https://ambientcg.com/view?id=Paper006
- Surface Imperfections 011: https://ambientcg.com/view?id=SurfaceImperfections011

ambientCG releases these downloadable asset files under the Creative Commons CC0 1.0 Universal public-domain dedication. The app stores optimized 512×512 derivatives for reading-background texture and material layers. No third-party application code or proprietary reading-app artwork is included.

The four embedded derivatives serve different layers rather than color presets:

- `reader_texture_paper_grain.png`: paper-grain texture;
- `reader_texture_paper_fiber.png`: fibrous-paper texture;
- `reader_material_frosted.png`: broad frosted surface variation;
- `reader_material_patina.png`: aged-paper surface variation.

Users may independently set the base color, texture layer, and material layer. Both texture and material include a `纯净` option that disables that layer.
