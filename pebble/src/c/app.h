#pragma once
#include <pebble.h>

// What the phone says (kept in step with logic.js STATUS).
typedef enum {
  STATUS_OK = 0,
  STATUS_NOT_CONNECTED = 1,
  STATUS_OFFLINE = 2,
  STATUS_LOGGED = 3,
  STATUS_QUEUED = 4,
  STATUS_FAILED = 5,
} PhoneStatus;

// A tap, in the order of WatchLogKind (kept in step with logic.js LOG_KINDS).
typedef enum {
  LOG_MOOD = 0,
  LOG_WATER,
  LOG_COFFEE,
  LOG_TOILET,
  LOG_LEAK,
  LOG_BOWEL,
  LOG_DOSE_TAKEN,
  LOG_HABIT,
  LOG_HOT_FLASH,
  LOG_SYMPTOM,
} LogKind;

// Features the watch shows (kept in step with logic.js MODULE_BITS).
#define MODULE_MIND 1
#define MODULE_NUTRITION 2
#define MODULE_CONTINENCE 4
#define MODULE_MEDICATIONS 8
#define MODULE_HABITS 16
#define MODULE_CYCLE 32
#define MODULE_SYMPTOMS 64
// Not a feature: set unless the person has turned water off (the water tap and Today's water line).
#define MODULE_WATER 128

#define MAX_ITEMS 10
#define NAME_SIZE 64
#define TIME_SIZE 12

typedef struct {
  char time[TIME_SIZE];
  char name[NAME_SIZE];
  bool taken;
} Dose;

typedef struct {
  char name[NAME_SIZE];
  bool done;
} HabitItem;

typedef struct {
  bool loaded;      // Today's numbers have arrived at least once.
  bool connected;   // False once the phone says the watch isn't connected.
  bool offline;     // The last refresh couldn't reach the server.
  uint32_t modules;
  int readiness, sleep, activity;  // -1 when there isn't one.
  int steps, step_goal;
  int water, water_goal;
  int stress;                      // -1, or STRESSED, ENGAGED, RELAXED, RESTORED.
  int dose_count;
  Dose doses[MAX_ITEMS];
  int habit_count;
  HabitItem habits[MAX_ITEMS];
  char message[100];
  time_t updated_at;
} AppData;

extern AppData g_data;

// Colours: the Aurora purple and green where there's colour, black and white where there isn't.
#define COLOR_ACCENT PBL_IF_COLOR_ELSE(GColorIndigo, GColorBlack)
#define COLOR_GOOD PBL_IF_COLOR_ELSE(GColorJaegerGreen, GColorBlack)
#define COLOR_SOFT PBL_IF_COLOR_ELSE(GColorLavenderIndigo, GColorBlack)
#define COLOR_WARN PBL_IF_COLOR_ELSE(GColorFolly, GColorBlack)

// data.c
void data_init(void);
void data_parse_doses(const char *packed);
void data_parse_habits(const char *packed);
void copy_utf8(char *dest, size_t size, const char *src, size_t length);
void format_thousands(int value, char *buffer, size_t size);
void format_litres(int ml, char *buffer, size_t size);
const char *stress_label(int stress);
int doses_left(void);
int habits_done(void);

// comms.c
void comms_init(void);
void comms_deinit(void);
void comms_refresh(void);
// Sends a tap. back_to_menu: once it's sent, go back to the main menu.
void comms_send(LogKind kind, int value, int index, bool back_to_menu);

// main_window.c
void main_window_push(void);
void main_window_reload(void);
Window *main_window_get(void);

// list_window.c: a menu of rows, used for every list in the app.
typedef struct ListWindow ListWindow;
typedef uint16_t (*ListCount)(void *context);
typedef void (*ListRow)(uint16_t row, const char **title, const char **subtitle, void *context);
typedef void (*ListSelect)(uint16_t row, void *context);
typedef void (*ListIcon)(GContext *ctx, GRect box, uint16_t row, bool highlighted, void *context);
typedef struct {
  const char *title;
  ListCount count;
  ListRow row;
  ListSelect select;
  ListIcon icon;  // Optional: draws a small picture at the start of each row.
  void *context;
} ListSpec;
ListWindow *list_window_push(const ListSpec *spec);
Window *list_window_get_window(ListWindow *list);
void list_windows_reload(void);
void list_windows_pop_to_main(void);

// today_window.c
void today_window_push(void);
void today_window_reload(void);

// pickers.c: mood, water, coffee, bladder, hot flash, symptom, doses, habits.
void picker_mood(void);
void picker_water(void);
void picker_coffee(void);
void picker_bladder(void);
void picker_hot_flash(void);
void picker_symptom(void);
void picker_doses(void);
void picker_habits(void);

// message_window.c: "Saving…", "Saved", "Not connected".
void toast_show(const char *text, bool good, uint32_t dismiss_after_ms);
void toast_waiting(void);
bool toast_is_waiting(void);
void toast_hide(void);
void not_connected_show(const char *text);
void not_connected_hide(void);
