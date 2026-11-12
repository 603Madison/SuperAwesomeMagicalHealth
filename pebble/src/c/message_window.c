#include "app.h"

// A full-screen message: a toast ("Saving…", "Saved") that closes itself, and the
// "Not connected" screen that stays until the phone has today's numbers.
typedef struct {
  Window *window;
  TextLayer *text;
  AppTimer *timer;
  char buffer[120];
  GColor background;
  bool waiting;
} Message;

static Message s_toast;
static Message s_offline;

// Short messages large; longer ones smaller, so they fit the smallest screens.
static GFont font_for(const char *text) {
  return fonts_get_system_font(strlen(text) > 36 ? FONT_KEY_GOTHIC_18_BOLD : FONT_KEY_GOTHIC_24_BOLD);
}

static void layout(Message *m) {
  Layer *root = window_get_root_layer(m->window);
  GRect bounds = layer_get_bounds(root);
  int inset = PBL_IF_ROUND_ELSE(24, 10);
  GRect frame = grect_inset(bounds, GEdgeInsets(inset));
  // Centre the words vertically.
  text_layer_set_font(m->text, font_for(m->buffer));
  GSize size = graphics_text_layout_get_content_size(m->buffer, font_for(m->buffer), frame,
                                                     GTextOverflowModeWordWrap, GTextAlignmentCenter);
  int top = (bounds.size.h - size.h) / 2 - 6;
  if (top < inset) top = inset;
  layer_set_frame(text_layer_get_layer(m->text), GRect(frame.origin.x, top, frame.size.w, bounds.size.h - top - inset));
}

static void window_load(Window *window) {
  Message *m = window_get_user_data(window);
  Layer *root = window_get_root_layer(window);
  m->text = text_layer_create(layer_get_bounds(root));
  text_layer_set_background_color(m->text, GColorClear);
  text_layer_set_text_color(m->text, GColorWhite);
  text_layer_set_text_alignment(m->text, GTextAlignmentCenter);
  text_layer_set_overflow_mode(m->text, GTextOverflowModeWordWrap);
  text_layer_set_text(m->text, m->buffer);
  layer_add_child(root, text_layer_get_layer(m->text));
  layout(m);
}

static void window_unload(Window *window) {
  Message *m = window_get_user_data(window);
  if (m->timer) app_timer_cancel(m->timer);
  m->timer = NULL;
  text_layer_destroy(m->text);
  m->text = NULL;
  window_destroy(m->window);
  m->window = NULL;
  m->waiting = false;
}

static void show(Message *m, const char *text, GColor background, bool animated) {
  snprintf(m->buffer, sizeof(m->buffer), "%s", text);
  m->background = background;
  if (!m->window) {
    m->window = window_create();
    window_set_user_data(m->window, m);
    window_set_window_handlers(m->window, (WindowHandlers) {
      .load = window_load,
      .unload = window_unload,
    });
    window_set_background_color(m->window, background);
    window_stack_push(m->window, animated);
  } else {
    window_set_background_color(m->window, background);
    if (m->text) {
      text_layer_set_text(m->text, m->buffer);
      layout(m);
    }
  }
}

static void hide(Message *m) {
  if (m->window) window_stack_remove(m->window, true);
}

static void toast_timeout(void *data) {
  s_toast.timer = NULL;
  hide(&s_toast);
}

void toast_show(const char *text, bool good, uint32_t dismiss_after_ms) {
  show(&s_toast, text, good ? COLOR_GOOD : COLOR_WARN, false);
  s_toast.waiting = false;
  if (s_toast.timer) app_timer_cancel(s_toast.timer);
  s_toast.timer = dismiss_after_ms ? app_timer_register(dismiss_after_ms, toast_timeout, NULL) : NULL;
}

void toast_waiting(void) {
  show(&s_toast, "Saving…", COLOR_ACCENT, false);
  if (s_toast.timer) app_timer_cancel(s_toast.timer);
  s_toast.timer = NULL;
  s_toast.waiting = true;
}

bool toast_is_waiting(void) {
  return s_toast.window && s_toast.waiting;
}

void toast_hide(void) {
  hide(&s_toast);
}

void not_connected_show(const char *text) {
  char message[120];
  snprintf(message, sizeof(message), "Not connected\n\n%s", text);
  show(&s_offline, message, COLOR_ACCENT, true);
}

void not_connected_hide(void) {
  hide(&s_offline);
}
