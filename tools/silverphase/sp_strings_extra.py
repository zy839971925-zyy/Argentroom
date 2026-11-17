#!/usr/bin/env python3
"""Hand-written Silverphase phone-editor strings (chrome, materials, help) and the XML writer.
Help texts: source noted per entry. iOS = Silverphase v1.2.5 modern_UI .help()/Strings.swift;
SF = Spektrafilm-for-Android tooltip of the same engine parameter (values/strings_*.xml).
Where the Silverphase row rescales a parameter, the text is reworded for the row's scale and
kept conservative (no physical claim beyond the source)."""
import json, sys

EXTRA = {
 # ---- chrome ----
 'sp_back_to_library': ('返回图库', 'Back to Library'),
 'sp_export': ('导出', 'Export'),
 'sp_menu': ('菜单', 'Menu'),
 'sp_done': ('完成', 'Done'),
 'sp_collapse_panel': ('收起面板', 'Collapse panel'),
 'sp_process_actions': ('工艺操作', 'Process actions'),
 'sp_reset_this_stage': ('重置本工艺', 'Reset This Stage'),
 'sp_choose_film': ('选择胶片', 'Choose Film'),
 'sp_choose_paper': ('选择相纸', 'Choose Paper'),
 'sp_undo': ('撤销', 'Undo'),
 'sp_redo': ('重做', 'Redo'),
 'sp_edit_history': ('编辑历史…', 'Edit History…'),
 'sp_reset_this_photo': ('重置当前照片', 'Reset This Photo'),
 'sp_zoom_to_fit': ('适合窗口', 'Zoom to Fit'),
 'sp_import_from_photos': ('从照片导入', 'Import from Photos'),
 'sp_import_from_files': ('从文件导入（含 RAW）', 'Import from Files (incl. RAW)'),
 'sp_compare_original': ('原图对比', 'Compare Original'),
 'sp_histogram': ('直方图', 'Histogram'),
 'sp_clip_warnings': ('溢出警告', 'Clipping Warnings'),
 'sp_loupe': ('100% 放大镜', '100% Loupe'),
 'sp_photo_info': ('照片信息', 'Photo Info'),
 'sp_share': ('分享', 'Share'),
 'sp_more_tools': ('更多工具（Android）…', 'More Tools (Android)…'),
 'sp_settings': ('设置', 'Settings'),
 'sp_about': ('关于', 'About'),
 'sp_more_tools_title': ('更多工具', 'More Tools'),
 'sp_more_tools_caption': ('Spektrafilm for Android 原有的工具，Silverphase iOS 没有对应界面；参数与上方七项工艺共用同一配方。', 'Tools from Spektrafilm for Android that Silverphase iOS has no screen for; they edit the same recipe as the seven stages.'),
 'sp_back': ('返回', 'Back'),
 'sp_state_original': ('原图', 'Original'),
 'sp_state_exporting': ('正在导出', 'Exporting'),
 'sp_state_reading': ('正在读取', 'Reading'),
 'sp_state_processing': ('处理中', 'Processing'),
 'sp_state_failed': ('显影失败', 'Development failed'),
 'sp_state_preview': ('适屏预览', 'Fit-to-window preview'),
 'sp_open_photo_to_start': ('打开照片开始编辑', 'Open a photo to start editing'),
 'sp_film_dev_print': ('胶片 · 显影 · 印相', 'Film · Development · Print'),
 'sp_open_photo': ('打开照片', 'Open Photo'),
 # stages
 'sp_stage_camera': ('拍摄', 'Camera'),
 'sp_stage_film': ('胶片', 'Film'),
 'sp_stage_development': ('显影', 'Development'),
 'sp_stage_print': ('彩色放大', 'Color Print'),
 'sp_stage_print_short': ('印相', 'Print'),
 'sp_stage_output': ('扫描', 'Scan'),
 'sp_stage_geometry': ('构图', 'Composition'),
 'sp_stage_film_edge': ('片边', 'Film Edge'),
 # controls
 'sp_state_on': ('已开启', 'On'),
 'sp_state_off': ('已关闭', 'Off'),
 'sp_unavailable_desc': ('%1$s，Android 引擎暂未实现', '%1$s, not yet in the Android engine'),
 'sp_engine_missing': ('引擎暂缺', 'Not in engine'),
 'sp_enter_value': ('输入%1$s', 'Enter %1$s'),
 'sp_range': ('范围 %1$s 至 %2$s', 'Range %1$s to %2$s'),
 'sp_ok': ('确定', 'OK'),
 'sp_cancel': ('取消', 'Cancel'),
 'sp_reference_size': ('基准尺寸 · %1$s', 'Reference Size · %1$s'),
 'sp_about_param': ('关于“%1$s”', 'About “%1$s”'),
 'sp_help_hint': ('长按名称或点 ⓘ 查看说明', 'Long-press a name or tap ⓘ for help'),
 # materials
 'sp_kind_mono': ('黑白', 'B&W'),
 'sp_kind_paper': ('相纸', 'Paper'),
 'sp_kind_positive': ('正片', 'Positive'),
 'sp_kind_negative': ('负片', 'Negative'),
 'sp_filter_all': ('全部', 'All'),
 'sp_filter_positive': ('正片', 'Positive'),
 'sp_filter_negative': ('负片', 'Negative'),
 'sp_filter_mono': ('黑白', 'B&W'),
 'sp_filter_experimental': ('实验', 'Experimental'),
 'sp_filter_favorites': ('收藏', 'Favorites'),
 'sp_filter_recent': ('最近', 'Recent'),
 'sp_positive_direct_scan': ('正片直接扫描', 'Positive · scanned directly'),
 'sp_no_print_profile': ('不使用相纸', 'No Print Profile'),
 'sp_scan_film_directly_sub': ('直接扫描胶片', 'Scan film directly'),
 'sp_print_material': ('印相材料', 'Print material'),
 'sp_film_material': ('感光材料', 'Film material'),
 'sp_current_film': ('当前胶片', 'Current Film'),
 'sp_current_paper': ('当前相纸', 'Current Paper'),
 'sp_change': ('更换', 'Change'),
 'sp_film_desc': ('胶片，%1$s', 'Film, %1$s'),
 'sp_paper_desc': ('相纸，%1$s', 'Paper, %1$s'),
 'sp_change_suffix': ('，更换', ', change'),
 'sp_search_materials': ('搜索材料', 'Search materials'),
 'sp_end_search': ('结束搜索', 'End search'),
 'sp_material_category': ('材料分类', 'Material category'),
 'sp_no_materials_match': ('没有符合筛选条件的材料', 'No materials match these filters'),
 'sp_add_favorite': ('收藏', 'Add to Favorites'),
 'sp_remove_favorite': ('取消收藏', 'Remove from Favorites'),
 'sp_selected': ('已选中', 'Selected'),
 'sp_not_selected': ('未选中', 'Not selected'),
 'sp_item_desc': ('%1$s，%2$s', '%1$s, %2$s'),
 'sp_no_cover': ('（无官方封面，使用通用图标）', '(no cover art; generic icon)'),
 # ---- help (ⓘ / long-press) ----
 'sp_help_metering': ('胶片自动测光决定曝光基准：均衡、中央主体、保护高光、中央加权为不同的测光权重；关闭时只用下方的曝光补偿。', 'Film auto-exposure sets the exposure baseline: Balanced, Center, Protect highlights and Center weighted weigh the frame differently; Off uses only the compensation below.'),  # SF widget_ae_tooltip + iOS labels
 'sp_help_exposure_comp': ('在自动测光结果上增减曝光，单位 EV（档）。向右更亮，向左更暗。', 'Adds a bias to the auto-exposure, in EV (stops). Right is brighter, left darker.'),  # SF editor_sim_camera_ev_tooltip
 'sp_help_wb_preset': ('RAW 的拍摄白平衡。日光、钨丝灯等为固定预设；自定时使用下方色温与色调。', 'The RAW capture white balance. Daylight, Tungsten etc. are fixed presets; Custom uses the temperature and tint below.'),
 'sp_help_temperature': ('自定义白平衡的色温，单位开尔文。', 'Colour temperature in kelvin for a custom white balance.'),  # SF editor_wb_temperature_tooltip
 'sp_help_tint': ('自定义白平衡的绿/品红色调；0 为中性。Android 版换算为 LibRaw 绿通道倍数，尚未与 iOS 逐值校准。', 'Green/magenta tint for a custom white balance; 0 is neutral. Android converts it to a LibRaw green multiplier, not yet calibrated value-for-value against iOS.'),  # SF editor_wb_tint_multiplier_tooltip
 'sp_help_lens_diffusion': ('模拟拍摄镜头前的柔光镜，作用于胶片曝光之前；与胶片光晕不同。', 'Simulates a diffusion filter in front of the camera lens, before film exposure. Separate from film halation.'),  # iOS lensFilterHelp
 'sp_help_diffusion_strength': ('柔光镜的档位强度（对应市售 1/8、1/4、1/2、1、2 等档）。', 'The filter grade (as in commercial 1/8, 1/4, 1/2, 1, 2 grades).'),  # SF editor_diff_strength_tooltip
 'sp_help_diffusion_spread': ('调整柔光的扩散尺度，不改变柔光强度；1× 保持所选柔光镜的模拟尺寸。', 'Changes the halo width without changing its strength. 1× keeps the selected filter\'s modeled size.'),  # iOS lensHaloRangeHelp
 'sp_help_enlarger_diffusion': ('模拟在放大机光路中加入柔光镜，作用于相纸曝光之前；与相纸眩光不同。', 'Simulates diffusion in the enlarger light path before the paper is exposed. Separate from print/scan glare.'),  # iOS enlargerSoftnessHelp
 'sp_help_film_format': ('胶片画幅的物理尺寸。它决定颗粒、光晕等按真实毫米尺度作用在画面上的大小：画幅越小，颗粒相对越明显。', 'The physical film format. It sets the real-millimetre scale at which grain and halation act on the picture: a smaller format makes grain relatively more visible.'),  # SF editor_sim_film_format_tooltip + iOS gate help
 'sp_help_grain': ('开启胶片颗粒的模拟。', 'Turns the film grain simulation on.'),
 'sp_help_sublayers': ('分层颗粒模型：每个感光层分别生成颗粒。黑白胶片不使用。', 'Sub-layer grain model: each emulsion layer grains separately. Not used by monochrome films.'),  # iOS helpSubLayerGrain
 'sp_help_grain_size': ('以 1× 为基准改变模拟的颗粒直径。画幅仍决定其物理尺度；不改变胶片配置文件。', 'Changes the modeled particle diameter relative to 1×. Film format still sets its physical scale; this does not alter the stock profile.'),  # iOS FilmSection .help
 'sp_help_grain_softness': ('平滑颗粒密度图像，与颗粒尺寸分开调节。1× 保持原有柔化，0× 关闭这一平滑；画面细微纹理也可能随之变柔。', 'Smooths the grained density image, separately from particle size. 1× preserves the original softness; 0× disables this smoothing. Fine image texture can also soften.'),  # iOS FilmSection T()
 'sp_help_halation': ('片基把光反射回乳剂层，形成高光周围的光晕。“跟随胶片”使用所选胶片自身的数据；关闭则不计算此阶段。', 'Light reflected back into the emulsion by the film base, the source of halation. Follow film uses the selected film\'s own data; Off switches the stage out of the render.'),  # iOS FilmSection .help
 'sp_help_halation_strength': ('所选光晕设置的倍数：1× 保持原样，最高 4× 放大反射。', 'A multiplier on the chosen halation setting: 1× leaves it as chosen, up to 4× amplifies the reflection.'),  # iOS FilmSection .help
 'sp_help_scatter': ('乳剂层内的整体光散射强度；1 为完整的物理散射。', 'Overall light scatter inside the emulsion; 1 is the full physical scatter.'),  # SF editor_hal_scatter_amount_tooltip
 'sp_help_headroom': ('在胶片曝光前估计数码高光裁切损失的光量。0 EV 使用原始光量；仅用于 CineStill 默认回反射模型。', 'Estimates light lost in clipped highlights before film exposure. 0 EV uses the recorded light; applies to CineStill\'s default back-reflection model.'),  # iOS FilmSection T()
 'sp_help_dev_contrast': ('负片密度曲线的伽马系数：大于 1 反差更高，小于 1 更平。', 'Gamma factor of the negative density curves: above 1 is more contrast, below 1 flatter.'),  # SF editor_exp_film_gamma_tooltip
 'sp_help_dir': ('DIR 成色剂在显影时抑制相邻层，影响胶片的色彩分离与边缘。黑白模型不使用。', 'DIR couplers inhibit neighbouring layers during development, shaping colour separation and edges. Not used by monochrome models.'),  # SF help_couplers_summary + iOS
 'sp_help_couplers_strength': ('DIR 成色剂抑制效果的整体倍数；1 为胶片默认。', 'Overall multiplier on the DIR coupler inhibition; 1 is the film default.'),  # SF editor_coup_amount_tooltip
 'sp_help_scan_direct': ('直接显示扫描后的胶片而不经相纸印放；正片自然如此。负片会显示原始的橙色底片。', 'Shows the scanned film directly instead of a print — natural for reversal (positive) film. A negative shows as the raw orange negative.'),  # SF editor_sim_slide_mode_tooltip
 'sp_help_di': ('数字中间片以 Cineon Log 输出，绕过相纸印相。Android 引擎暂无此分支。', 'Digital Intermediate outputs Cineon Log and bypasses the paper print. The Android engine has no such branch yet.'),  # iOS printDigitalIntermediateHelp (shortened)
 'sp_help_enlarger_exposure': ('改变虚拟放大机的曝光，以档为单位：向右印得更亮，向左更暗。', 'Changes the virtual enlarger\'s exposure, in stops: right prints lighter, left darker.'),  # SF editor_sim_print_exposure_tooltip; Silverphase stops = -log2(print_exposure)
 'sp_help_paper_contrast': ('改变所选相纸的密度响应，与胶片显影反差分开；1× 保持原相纸配置。', 'Reshapes the selected paper\'s density response, separate from film development contrast. 1× preserves the original paper profile.'),  # iOS PrintProfileSection .help
 'sp_help_paper_grade': ('放大机上的多级反差滤色：00 最软，5 最硬；中灰保持不变，只改变反差。', 'Multigrade filtration on the enlarger: 00 is softest, 5 hardest. Mid-grey stays put while contrast changes.'),  # SF editor_sim_paper_grade_tooltip
 'sp_help_filter_y': ('放大机黄滤色相对中性的偏移（Kodak CC 单位）。', 'Enlarger yellow filter shift from neutral, in Kodak CC units.'),  # SF editor_sim_print_y_shift_tooltip
 'sp_help_filter_m': ('放大机品红滤色相对中性的偏移（Kodak CC 单位）。', 'Enlarger magenta filter shift from neutral, in Kodak CC units.'),  # SF editor_sim_print_m_shift_tooltip
 'sp_help_print_effects': ('相纸预闪与相纸眩光的总开关。', 'The switch for paper pre-flash and paper glare.'),  # iOS clipPrintEffectsHelp
 'sp_help_preflash': ('相纸正式曝光前的均匀弱曝光，可打开最深的阴影、略降反差，基本不动高光。', 'A faint, even exposure of the paper before the real one; opens the deepest shadows and lowers contrast slightly, leaving highlights largely alone.'),  # SF help_preflash_summary/body
 'sp_help_scan_sharpen': ('扫描时的反锐化掩模（USM）。强度为倍率，1× 为默认。', 'Unsharp masking at the scan. Strength is a multiplier; 1× is the default.'),  # SF editor_sim_scan_unsharp_tooltip
 'sp_help_paper_glare': ('模拟观看或数字化已形成的相纸影像时的眩光；与胶片光晕和柔光放大不同。', 'Glare from viewing or digitising the finished paper image; separate from film halation and enlarger diffusion.'),  # iOS paperGlareHelp
 'sp_help_glare_strength': ('眩光光量的倍数；1× 为引擎默认光量。', 'A multiplier on the glare light; 1× is the engine default.'),  # SF editor_glare_percent_tooltip (rescaled)
 'sp_help_highlight_expansion': ('展开相纸成像中压缩的高光层次。Android 引擎暂无此阶段。', 'Expands compressed highlight detail in the completed paper image. The Android engine has no such stage yet.'),  # iOS highlightExpansionHelp
 'sp_help_date': ('把相机日期背的印字曝光到底片上，让数字与画面一起显影、发光并带有颗粒，而不是贴在上面。', 'Exposes a date-back imprint onto the negative, so the digits develop, glow and grain with the picture instead of being pasted on top.'),  # SF editor_sim_date_stamp_tooltip
 'sp_help_date_ev': ('日期 LED 对胶片的曝光强度，以高于中灰的档数表示；越亮越向画面晕开。', 'How strongly the LED exposes the film, in stops above mid-grey; brighter digits bloom more.'),  # SF editor_sim_date_stamp_brightness_tooltip
 'sp_help_date_size': ('数字高度，以 Android 默认尺寸为 1×。', 'Digit height, with the Android default as 1×.'),  # SF editor_sim_date_stamp_size_tooltip (rescaled)
 'sp_help_unavailable': ('Silverphase iOS 有此参数，但 Android 引擎尚无对应阶段，因此保留位置并禁用，不用相似效果冒充。', 'Silverphase iOS has this parameter but the Android engine has no matching stage yet, so it keeps its place and stays disabled rather than faking a look-alike.'),
}

def esc(t):
    return t.replace('&', '&amp;').replace('<', '&lt;').replace("'", "\\'").replace('"', '\\"')

def write(res_dir):
    table = json.load(open('tools/silverphase/sp_table.json'))
    for k, (zh, en) in EXTRA.items():
        table[k] = [zh, en]
    for lang, idx in (('values', 1), ('values-zh-rCN', 0)):
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 '<!-- Silverphase phone editor (GPLv3). English from Silverphase v1.2.5 T() pairs where they exist;',
                 '     generated by tools/silverphase/sp_strings*.py. Only en + zh-rCN exist; other locales fall back to English. -->',
                 '<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation">' if lang == 'values' else '<resources>']
        for k in sorted(table):
            v = table[k][idx]
            fmt = '' if '%1$' in v else ' formatted="false"' if '%' in v else ''
            lines.append(f'    <string name="{k}"{fmt}>{esc(v)}</string>')
        lines.append('</resources>\n')
        open(f'{res_dir}/{lang}/strings_silverphase.xml', 'w').write('\n'.join(lines))
    print(len(table), 'keys written')

if __name__ == '__main__':
    write(sys.argv[1])
