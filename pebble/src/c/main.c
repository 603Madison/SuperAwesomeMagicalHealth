#include "app.h"

// SuperAwesomeMagicalHealth on the wrist: today's scores, and one-tap logging.

static AppTimer *s_first_refresh;

static void first_refresh(void *data) {
  s_first_refresh = NULL;
  // The phone sends today's numbers when it starts; ask again in case that message was missed.
  if (!g_data.loaded && g_data.connected) comms_refresh();
}

static void init(void) {
  data_init();
  comms_init();
  main_window_push();
  s_first_refresh = app_timer_register(3000, first_refresh, NULL);
}

static void deinit(void) {
  if (s_first_refresh) app_timer_cancel(s_first_refresh);
  comms_deinit();
  window_stack_pop_all(false);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
