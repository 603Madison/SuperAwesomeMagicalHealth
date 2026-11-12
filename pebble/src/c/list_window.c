#include "app.h"

// A window with a status bar and a menu, built from a ListSpec. Every list in the app is one.
struct ListWindow {
  Window *window;
  StatusBarLayer *status_bar;
  MenuLayer *menu;
  ListSpec spec;
};

#define MAX_OPEN 6
static ListWindow *s_open[MAX_OPEN];

static void remember(ListWindow *list) {
  for (int i = 0; i < MAX_OPEN; i++) {
    if (!s_open[i]) {
      s_open[i] = list;
      return;
    }
  }
}

static void forget(ListWindow *list) {
  for (int i = 0; i < MAX_OPEN; i++) {
    if (s_open[i] == list) s_open[i] = NULL;
  }
}

static uint16_t get_num_rows(MenuLayer *menu, uint16_t section, void *context) {
  ListWindow *list = context;
  return list->spec.count(list->spec.context);
}

static int16_t get_header_height(MenuLayer *menu, uint16_t section, void *context) {
  ListWindow *list = context;
  return list->spec.title ? MENU_CELL_BASIC_HEADER_HEIGHT + 4 : 0;
}

static uint16_t get_num_sections(MenuLayer *menu, void *context) {
  return 1;
}

static void draw_header(GContext *ctx, const Layer *cell, uint16_t section, void *context) {
  ListWindow *list = context;
  if (!list->spec.title) return;
  GRect bounds = layer_get_bounds(cell);
  graphics_context_set_text_color(ctx, COLOR_ACCENT);
  graphics_draw_text(ctx, list->spec.title, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
                     GRect(PBL_IF_ROUND_ELSE(0, 6), -2, bounds.size.w - PBL_IF_ROUND_ELSE(0, 12), bounds.size.h),
                     GTextOverflowModeTrailingEllipsis, PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft), NULL);
}

static int16_t get_cell_height(MenuLayer *menu, MenuIndex *index, void *context) {
  return PBL_IF_ROUND_ELSE(menu_layer_is_index_selected(menu, index) ? 62 : 44, 48);
}

static void draw_row(GContext *ctx, const Layer *cell, MenuIndex *index, void *context) {
  ListWindow *list = context;
  const char *title = NULL;
  const char *subtitle = NULL;
  list->spec.row(index->row, &title, &subtitle, list->spec.context);
  if (list->spec.icon) {
    GRect bounds = layer_get_bounds(cell);
    bool highlighted = menu_cell_layer_is_highlighted(cell);
    int box = 26;
#if defined(PBL_ROUND)
    // On a round screen rows are centred: the picture sits above the words when selected.
    if (highlighted) {
      GRect pic = GRect((bounds.size.w - box) / 2, 2, box, box);
      list->spec.icon(ctx, pic, index->row, highlighted, list->spec.context);
      graphics_context_set_text_color(ctx, GColorWhite);
      graphics_draw_text(ctx, title, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD), GRect(0, box, bounds.size.w, 30),
                         GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
      return;
    }
    menu_cell_basic_draw(ctx, cell, title, subtitle, NULL);
#else
    GRect pic = GRect(6, (bounds.size.h - box) / 2, box, box);
    list->spec.icon(ctx, pic, index->row, highlighted, list->spec.context);
    graphics_context_set_text_color(ctx, highlighted ? GColorWhite : GColorBlack);
    int left = 6 + box + 8;
    int top = subtitle ? 2 : 8;
    graphics_draw_text(ctx, title, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD), GRect(left, top, bounds.size.w - left - 4, 28),
                       GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
    if (subtitle) {
      graphics_draw_text(ctx, subtitle, fonts_get_system_font(FONT_KEY_GOTHIC_14), GRect(left, top + 26, bounds.size.w - left - 4, 18),
                         GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
    }
#endif
    return;
  }
  menu_cell_basic_draw(ctx, cell, title, subtitle, NULL);
}

static void select_click(MenuLayer *menu, MenuIndex *index, void *context) {
  ListWindow *list = context;
  if (list->spec.select) list->spec.select(index->row, list->spec.context);
}

static void window_load(Window *window) {
  ListWindow *list = window_get_user_data(window);
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);

  list->status_bar = status_bar_layer_create();
  status_bar_layer_set_colors(list->status_bar, COLOR_ACCENT, GColorWhite);
  status_bar_layer_set_separator_mode(list->status_bar, StatusBarLayerSeparatorModeNone);
  layer_add_child(root, status_bar_layer_get_layer(list->status_bar));

  GRect frame = GRect(0, STATUS_BAR_LAYER_HEIGHT, bounds.size.w, bounds.size.h - STATUS_BAR_LAYER_HEIGHT);
  list->menu = menu_layer_create(frame);
  menu_layer_set_callbacks(list->menu, list, (MenuLayerCallbacks) {
    .get_num_sections = get_num_sections,
    .get_num_rows = get_num_rows,
    .get_header_height = get_header_height,
    .draw_header = draw_header,
    .get_cell_height = get_cell_height,
    .draw_row = draw_row,
    .select_click = select_click,
  });
  menu_layer_set_normal_colors(list->menu, GColorWhite, GColorBlack);
  menu_layer_set_highlight_colors(list->menu, COLOR_ACCENT, GColorWhite);
  menu_layer_set_click_config_onto_window(list->menu, window);
  layer_add_child(root, menu_layer_get_layer(list->menu));
}

static void window_unload(Window *window) {
  ListWindow *list = window_get_user_data(window);
  forget(list);
  menu_layer_destroy(list->menu);
  status_bar_layer_destroy(list->status_bar);
  window_destroy(window);
  free(list);
}

ListWindow *list_window_push(const ListSpec *spec) {
  ListWindow *list = malloc(sizeof(ListWindow));
  if (!list) return NULL;
  memset(list, 0, sizeof(ListWindow));
  list->spec = *spec;
  list->window = window_create();
  window_set_user_data(list->window, list);
  window_set_background_color(list->window, GColorWhite);
  window_set_window_handlers(list->window, (WindowHandlers) {
    .load = window_load,
    .unload = window_unload,
  });
  remember(list);
  window_stack_push(list->window, true);
  return list;
}

Window *list_window_get_window(ListWindow *list) {
  return list ? list->window : NULL;
}

void list_windows_reload(void) {
  for (int i = 0; i < MAX_OPEN; i++) {
    ListWindow *list = s_open[i];
    if (!list || !list->menu) continue;
    // Keep the selection in range when a list got shorter.
    uint16_t count = list->spec.count(list->spec.context);
    MenuIndex selected = menu_layer_get_selected_index(list->menu);
    menu_layer_reload_data(list->menu);
    if (count > 0 && selected.row >= count) {
      menu_layer_set_selected_index(list->menu, MenuIndex(0, count - 1), MenuRowAlignCenter, false);
    }
  }
}

// Closes every list above the main menu (after a one-off tap, like a mood).
void list_windows_pop_to_main(void) {
  Window *main = main_window_get();
  while (window_stack_get_top_window() && window_stack_get_top_window() != main && window_stack_contains_window(main)) {
    window_stack_pop(false);
  }
}
