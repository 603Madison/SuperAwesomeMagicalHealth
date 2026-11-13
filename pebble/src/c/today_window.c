#include "app.h"

// Today: the three scores, steps and water against their goals (water only while it's on), and how the day feels.
static Window *s_window;
static StatusBarLayer *s_status_bar;
static ScrollLayer *s_scroll;
static Layer *s_content;

#define MARGIN PBL_IF_ROUND_ELSE(22, 8)
#define SCORES_HEIGHT 52
#define BAR_ROW_HEIGHT 50
#define LINE_HEIGHT 28

static void draw_score(GContext *ctx, GRect box, int value, const char *label) {
  char text[12];
  if (value >= 0) snprintf(text, sizeof(text), "%d", value);
  else snprintf(text, sizeof(text), "–");
  graphics_context_set_text_color(ctx, COLOR_ACCENT);
  graphics_draw_text(ctx, text, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD), GRect(box.origin.x, box.origin.y, box.size.w, 32),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  graphics_context_set_text_color(ctx, GColorBlack);
  graphics_draw_text(ctx, label, fonts_get_system_font(FONT_KEY_GOTHIC_14), GRect(box.origin.x, box.origin.y + 31, box.size.w, 18),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
}

// A label, "have of goal" and a bar filled to the share of the goal reached.
static void draw_bar_row(GContext *ctx, GRect box, const char *label, const char *value, int have, int goal) {
  graphics_context_set_text_color(ctx, GColorBlack);
  graphics_draw_text(ctx, label, fonts_get_system_font(FONT_KEY_GOTHIC_14), GRect(box.origin.x, box.origin.y, box.size.w, 18),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
  graphics_draw_text(ctx, value, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD), GRect(box.origin.x, box.origin.y + 13, box.size.w, 24),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
  GRect track = GRect(box.origin.x, box.origin.y + 38, box.size.w, 6);
  graphics_context_set_fill_color(ctx, PBL_IF_COLOR_ELSE(GColorLightGray, GColorWhite));
  graphics_fill_rect(ctx, track, 3, GCornersAll);
#if !defined(PBL_COLOR)
  graphics_context_set_stroke_color(ctx, GColorBlack);
  graphics_draw_round_rect(ctx, track, 3);
#endif
  if (goal > 0 && have > 0) {
    int width = have >= goal ? track.size.w : (int)((int64_t)track.size.w * have / goal);
    if (width < 6) width = 6;
    graphics_context_set_fill_color(ctx, have >= goal ? COLOR_GOOD : COLOR_SOFT);
    graphics_fill_rect(ctx, GRect(track.origin.x, track.origin.y, width, track.size.h), 3, GCornersAll);
  }
}

static bool shows_water(void) {
  return (g_data.modules & MODULE_WATER) != 0;
}

static int content_height(void) {
  int h = 6 + SCORES_HEIGHT + (shows_water() ? 2 : 1) * BAR_ROW_HEIGHT;
  if (stress_label(g_data.stress)) h += LINE_HEIGHT;
  if (!g_data.loaded) h += LINE_HEIGHT;
  return h + PBL_IF_ROUND_ELSE(40, 10);
}

static void draw_content(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  int x = MARGIN;
  int w = bounds.size.w - 2 * MARGIN;
  int y = PBL_IF_ROUND_ELSE(10, 6);

  int column = w / 3;
  bool wide = column >= 58;
  draw_score(ctx, GRect(x, y, column, SCORES_HEIGHT), g_data.readiness, wide ? "Readiness" : "Ready");
  draw_score(ctx, GRect(x + column, y, column, SCORES_HEIGHT), g_data.sleep, "Sleep");
  draw_score(ctx, GRect(x + 2 * column, y, w - 2 * column, SCORES_HEIGHT), g_data.activity, "Activity");
  y += SCORES_HEIGHT;

  char have[16], goal[16], text[40];
  format_thousands(g_data.steps, have, sizeof(have));
  if (g_data.step_goal > 0) {
    format_thousands(g_data.step_goal, goal, sizeof(goal));
    snprintf(text, sizeof(text), "%s of %s", have, goal);
  } else {
    snprintf(text, sizeof(text), "%s", have);
  }
  draw_bar_row(ctx, GRect(x, y, w, BAR_ROW_HEIGHT), "Steps", text, g_data.steps, g_data.step_goal);
  y += BAR_ROW_HEIGHT;

  if (shows_water()) {
    format_litres(g_data.water, have, sizeof(have));
    if (g_data.water_goal > 0) {
      format_litres(g_data.water_goal, goal, sizeof(goal));
      snprintf(text, sizeof(text), "%s of %s L", have, goal);
    } else {
      snprintf(text, sizeof(text), "%s L", have);
    }
    draw_bar_row(ctx, GRect(x, y, w, BAR_ROW_HEIGHT), "Water", text, g_data.water, g_data.water_goal);
    y += BAR_ROW_HEIGHT;
  }

  const char *stress = stress_label(g_data.stress);
  if (stress) {
    snprintf(text, sizeof(text), "Right now: %s", stress);
    graphics_context_set_text_color(ctx, GColorBlack);
    graphics_draw_text(ctx, text, fonts_get_system_font(FONT_KEY_GOTHIC_18), GRect(x, y, w, LINE_HEIGHT),
                       GTextOverflowModeTrailingEllipsis, PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft), NULL);
    y += LINE_HEIGHT;
  }
  if (!g_data.loaded) {
    graphics_context_set_text_color(ctx, PBL_IF_COLOR_ELSE(GColorDarkGray, GColorBlack));
    graphics_draw_text(ctx, g_data.offline ? "Can't reach your server" : "Updating…", fonts_get_system_font(FONT_KEY_GOTHIC_14),
                       GRect(x, y, w, LINE_HEIGHT), GTextOverflowModeTrailingEllipsis,
                       PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft), NULL);
  }
}

static void resize(void) {
  if (!s_scroll) return;
  GRect frame = layer_get_frame(scroll_layer_get_layer(s_scroll));
  int height = content_height();
  layer_set_frame(s_content, GRect(0, 0, frame.size.w, height));
  scroll_layer_set_content_size(s_scroll, GSize(frame.size.w, height));
}

static void window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);
  s_status_bar = status_bar_layer_create();
  status_bar_layer_set_colors(s_status_bar, COLOR_ACCENT, GColorWhite);
  layer_add_child(root, status_bar_layer_get_layer(s_status_bar));

  GRect frame = GRect(0, STATUS_BAR_LAYER_HEIGHT, bounds.size.w, bounds.size.h - STATUS_BAR_LAYER_HEIGHT);
  s_scroll = scroll_layer_create(frame);
  scroll_layer_set_click_config_onto_window(s_scroll, window);
  scroll_layer_set_shadow_hidden(s_scroll, true);
  s_content = layer_create(GRect(0, 0, frame.size.w, content_height()));
  layer_set_update_proc(s_content, draw_content);
  scroll_layer_add_child(s_scroll, s_content);
  layer_add_child(root, scroll_layer_get_layer(s_scroll));
  resize();
}

static void window_unload(Window *window) {
  layer_destroy(s_content);
  scroll_layer_destroy(s_scroll);
  status_bar_layer_destroy(s_status_bar);
  window_destroy(s_window);
  s_content = NULL;
  s_scroll = NULL;
  s_status_bar = NULL;
  s_window = NULL;
}

void today_window_push(void) {
  if (s_window) return;
  s_window = window_create();
  window_set_background_color(s_window, GColorWhite);
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = window_load,
    .unload = window_unload,
  });
  window_stack_push(s_window, true);
}

void today_window_reload(void) {
  if (!s_content) return;
  resize();
  layer_mark_dirty(s_content);
}
