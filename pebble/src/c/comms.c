#include "app.h"

#define REFRESH_EVERY_MS (30 * 60 * 1000)
#define ANSWER_WITHIN_MS (15 * 1000)

static AppTimer *s_refresh_timer;
static AppTimer *s_answer_timer;

static int read_int(DictionaryIterator *iter, uint32_t key, int fallback) {
  Tuple *t = dict_find(iter, key);
  return t ? (int)t->value->int32 : fallback;
}

static void no_answer(void *data) {
  s_answer_timer = NULL;
  if (toast_is_waiting()) {
    toast_show("Your phone didn't answer. Is the Pebble app open?", false, 3000);
  }
}

static void answered(void) {
  if (s_answer_timer) {
    app_timer_cancel(s_answer_timer);
    s_answer_timer = NULL;
  }
}

static void message_text(DictionaryIterator *iter) {
  Tuple *t = dict_find(iter, MESSAGE_KEY_Message);
  if (t && t->type == TUPLE_CSTRING) {
    copy_utf8(g_data.message, sizeof(g_data.message), t->value->cstring, strlen(t->value->cstring));
  } else {
    g_data.message[0] = '\0';
  }
}

static void inbox_received(DictionaryIterator *iter, void *context) {
  Tuple *status_tuple = dict_find(iter, MESSAGE_KEY_Status);
  if (!status_tuple) return;
  PhoneStatus status = (PhoneStatus)status_tuple->value->int32;
  message_text(iter);
  switch (status) {
    case STATUS_OK: {
      g_data.loaded = true;
      g_data.connected = true;
      g_data.offline = false;
      g_data.modules = (uint32_t)read_int(iter, MESSAGE_KEY_Modules, 0);
      g_data.readiness = read_int(iter, MESSAGE_KEY_Readiness, -1);
      g_data.sleep = read_int(iter, MESSAGE_KEY_Sleep, -1);
      g_data.activity = read_int(iter, MESSAGE_KEY_Activity, -1);
      g_data.steps = read_int(iter, MESSAGE_KEY_Steps, 0);
      g_data.step_goal = read_int(iter, MESSAGE_KEY_StepGoal, 0);
      g_data.water = read_int(iter, MESSAGE_KEY_Water, 0);
      g_data.water_goal = read_int(iter, MESSAGE_KEY_WaterGoal, 0);
      g_data.stress = read_int(iter, MESSAGE_KEY_Stress, -1);
      Tuple *doses = dict_find(iter, MESSAGE_KEY_Doses);
      data_parse_doses(doses && doses->type == TUPLE_CSTRING ? doses->value->cstring : "");
      Tuple *habits = dict_find(iter, MESSAGE_KEY_Habits);
      data_parse_habits(habits && habits->type == TUPLE_CSTRING ? habits->value->cstring : "");
      g_data.updated_at = time(NULL);
      not_connected_hide();
      break;
    }
    case STATUS_NOT_CONNECTED:
      g_data.connected = false;
      answered();
      if (toast_is_waiting()) toast_hide();
      not_connected_show(g_data.message[0] ? g_data.message : "Open this app's settings in the Pebble app to connect it to your server.");
      break;
    case STATUS_OFFLINE:
      g_data.offline = true;
      break;
    case STATUS_LOGGED:
      answered();
      vibes_short_pulse();
      toast_show("Saved", true, 1200);
      break;
    case STATUS_QUEUED:
      answered();
      vibes_short_pulse();
      toast_show(g_data.message[0] ? g_data.message : "Saved on your phone. It'll send later.", true, 2500);
      break;
    case STATUS_FAILED:
      answered();
      vibes_double_pulse();
      toast_show(g_data.message[0] ? g_data.message : "That didn't save.", false, 3000);
      break;
  }
  main_window_reload();
  list_windows_reload();
  today_window_reload();
}

static void inbox_dropped(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "Message dropped: %d", (int)reason);
}

static void outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "Message not sent: %d", (int)reason);
}

static bool send_ints(const uint32_t *keys, const int32_t *values, int count) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) return false;
  for (int i = 0; i < count; i++) {
    dict_write_int32(iter, keys[i], values[i]);
  }
  return app_message_outbox_send() == APP_MSG_OK;
}

static void refresh_timer(void *data) {
  comms_refresh();
  s_refresh_timer = app_timer_register(REFRESH_EVERY_MS, refresh_timer, NULL);
}

void comms_refresh(void) {
  uint32_t keys[] = { MESSAGE_KEY_Refresh };
  int32_t values[] = { 1 };
  send_ints(keys, values, 1);
}

void comms_send(LogKind kind, int value, int index, bool back_to_menu) {
  uint32_t keys[] = { MESSAGE_KEY_Action, MESSAGE_KEY_Value, MESSAGE_KEY_Index };
  int32_t values[] = { (int32_t)kind, (int32_t)value, (int32_t)index };
  if (back_to_menu) list_windows_pop_to_main();
  if (!send_ints(keys, values, 3)) {
    vibes_double_pulse();
    toast_show("Couldn't reach your phone. Try again in a moment.", false, 2500);
    return;
  }
  toast_waiting();
  answered();
  s_answer_timer = app_timer_register(ANSWER_WITHIN_MS, no_answer, NULL);
}

void comms_init(void) {
  app_message_register_inbox_received(inbox_received);
  app_message_register_inbox_dropped(inbox_dropped);
  app_message_register_outbox_failed(outbox_failed);
  // Today with two lists of ten fits comfortably; taps are three numbers.
  app_message_open(1024, 128);
  // The phone sends today's numbers when it starts; this keeps them fresh while the app is open.
  s_refresh_timer = app_timer_register(REFRESH_EVERY_MS, refresh_timer, NULL);
}

void comms_deinit(void) {
  if (s_refresh_timer) app_timer_cancel(s_refresh_timer);
  if (s_answer_timer) app_timer_cancel(s_answer_timer);
  s_refresh_timer = NULL;
  s_answer_timer = NULL;
  app_message_deregister_callbacks();
}
