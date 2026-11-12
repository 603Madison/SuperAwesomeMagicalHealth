#include "app.h"

// The main menu: Today, then a row for each thing to log, for the features the person has on.
typedef enum {
  ITEM_TODAY,
  ITEM_MOOD,
  ITEM_WATER,
  ITEM_COFFEE,
  ITEM_BLADDER,
  ITEM_MEDS,
  ITEM_HABITS,
  ITEM_HOT_FLASH,
  ITEM_SYMPTOM,
  ITEM_UPDATE,
  ITEM_COUNT,
} Item;

static ListWindow *s_main;
static Item s_items[ITEM_COUNT];
static char s_today_sub[48];
static char s_water_sub[32];
static char s_meds_sub[32];
static char s_habits_sub[32];
static char s_update_sub[40];

static bool has(uint32_t module) {
  return (g_data.modules & module) != 0;
}

static uint16_t build_items(void) {
  uint16_t n = 0;
  s_items[n++] = ITEM_TODAY;
  if (has(MODULE_MIND)) s_items[n++] = ITEM_MOOD;
  if (has(MODULE_NUTRITION)) {
    s_items[n++] = ITEM_WATER;
    s_items[n++] = ITEM_COFFEE;
  }
  if (has(MODULE_CONTINENCE)) s_items[n++] = ITEM_BLADDER;
  if (has(MODULE_MEDICATIONS)) s_items[n++] = ITEM_MEDS;
  if (has(MODULE_HABITS)) s_items[n++] = ITEM_HABITS;
  if (has(MODULE_CYCLE)) s_items[n++] = ITEM_HOT_FLASH;
  if (has(MODULE_SYMPTOMS)) s_items[n++] = ITEM_SYMPTOM;
  s_items[n++] = ITEM_UPDATE;
  return n;
}

static uint16_t count(void *context) {
  return build_items();
}

static void row(uint16_t index, const char **title, const char **subtitle, void *context) {
  build_items();
  switch (s_items[index]) {
    case ITEM_TODAY:
      *title = "Today";
      if (!g_data.loaded) {
        *subtitle = g_data.offline ? "Can't reach your server" : "Updating…";
      } else {
        char steps[16];
        format_thousands(g_data.steps, steps, sizeof(steps));
        // Narrow screens have room for the steps only; the scores are a click away.
        if (g_data.readiness >= 0 && PBL_DISPLAY_WIDTH >= 200) {
          snprintf(s_today_sub, sizeof(s_today_sub), "Readiness %d · %s steps", g_data.readiness, steps);
        } else {
          snprintf(s_today_sub, sizeof(s_today_sub), "%s steps", steps);
        }
        *subtitle = s_today_sub;
      }
      break;
    case ITEM_MOOD:
      *title = "Mood";
      *subtitle = "How are you feeling?";
      break;
    case ITEM_WATER: {
      char have[8], goal[8];
      format_litres(g_data.water, have, sizeof(have));
      format_litres(g_data.water_goal, goal, sizeof(goal));
      *title = "Water";
      if (g_data.water_goal > 0) snprintf(s_water_sub, sizeof(s_water_sub), "%s of %s L", have, goal);
      else snprintf(s_water_sub, sizeof(s_water_sub), "%s L today", have);
      *subtitle = s_water_sub;
      break;
    }
    case ITEM_COFFEE:
      *title = "Coffee";
      *subtitle = "Log a cup";
      break;
    case ITEM_BLADDER:
      *title = "Bladder & bowel";
      *subtitle = "Trip, leak or bowel movement";
      break;
    case ITEM_MEDS: {
      *title = "Medications";
      int left = doses_left();
      if (g_data.dose_count == 0) snprintf(s_meds_sub, sizeof(s_meds_sub), "Nothing due today");
      else if (left == 0) snprintf(s_meds_sub, sizeof(s_meds_sub), "All taken");
      else snprintf(s_meds_sub, sizeof(s_meds_sub), "%d to take", left);
      *subtitle = s_meds_sub;
      break;
    }
    case ITEM_HABITS:
      *title = "Habits";
      if (g_data.habit_count == 0) snprintf(s_habits_sub, sizeof(s_habits_sub), "None for today");
      else snprintf(s_habits_sub, sizeof(s_habits_sub), "%d of %d done", habits_done(), g_data.habit_count);
      *subtitle = s_habits_sub;
      break;
    case ITEM_HOT_FLASH:
      *title = "Hot flash";
      *subtitle = "Log one now";
      break;
    case ITEM_SYMPTOM:
      *title = "Symptom";
      *subtitle = "What, and how bad";
      break;
    case ITEM_UPDATE:
    default:
      *title = "Update now";
      if (g_data.updated_at) {
        char clock[8];
        strftime(clock, sizeof(clock), clock_is_24h_style() ? "%H:%M" : "%I:%M", localtime(&g_data.updated_at));
        snprintf(s_update_sub, sizeof(s_update_sub), "Updated %s", clock[0] == '0' && !clock_is_24h_style() ? clock + 1 : clock);
        *subtitle = s_update_sub;
      } else {
        *subtitle = NULL;
      }
      break;
  }
}

static void select_row(uint16_t index, void *context) {
  build_items();
  switch (s_items[index]) {
    case ITEM_TODAY: today_window_push(); break;
    case ITEM_MOOD: picker_mood(); break;
    case ITEM_WATER: picker_water(); break;
    case ITEM_COFFEE: picker_coffee(); break;
    case ITEM_BLADDER: picker_bladder(); break;
    case ITEM_MEDS: picker_doses(); break;
    case ITEM_HABITS: picker_habits(); break;
    case ITEM_HOT_FLASH: picker_hot_flash(); break;
    case ITEM_SYMPTOM: picker_symptom(); break;
    case ITEM_UPDATE:
    default:
      comms_refresh();
      vibes_short_pulse();
      break;
  }
}

void main_window_push(void) {
  ListSpec spec = {
    .title = NULL,
    .count = count,
    .row = row,
    .select = select_row,
  };
  s_main = list_window_push(&spec);
}

void main_window_reload(void) {
  list_windows_reload();
}

Window *main_window_get(void) {
  return list_window_get_window(s_main);
}
