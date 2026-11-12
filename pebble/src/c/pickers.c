#include "app.h"

// A fixed list of choices: a title and subtitle for each, and what choosing one sends.
typedef struct {
  const char *title;
  const char *subtitle;
  int value;
} Choice;

typedef struct {
  const Choice *choices;
  uint16_t count;
  LogKind kind;
  int index;  // For symptoms: which symptom.
} ChoiceList;

static uint16_t choice_count(void *context) {
  return ((ChoiceList *)context)->count;
}

static void choice_row(uint16_t row, const char **title, const char **subtitle, void *context) {
  ChoiceList *list = context;
  *title = list->choices[row].title;
  *subtitle = list->choices[row].subtitle;
}

static void choice_select(uint16_t row, void *context) {
  ChoiceList *list = context;
  comms_send(list->kind, list->choices[row].value, list->index, true);
}

static void push_choices(const char *title, ChoiceList *list, ListIcon icon) {
  ListSpec spec = {
    .title = title,
    .count = choice_count,
    .row = choice_row,
    .select = choice_select,
    .icon = icon,
    .context = list,
  };
  list_window_push(&spec);
}

// ---- Mood: five faces, great at the top ----

static const Choice MOODS[] = {
  { "Great", NULL, 5 },
  { "Good", NULL, 4 },
  { "Okay", NULL, 3 },
  { "Low", NULL, 2 },
  { "Very low", NULL, 1 },
};
static ChoiceList s_moods = { MOODS, ARRAY_LENGTH(MOODS), LOG_MOOD, 0 };

// The top of a circle, [half] degrees either side of 12 o'clock (Pebble's angles start there).
static void draw_top_arc(GContext *ctx, GRect box, int half) {
  graphics_draw_arc(ctx, box, GOvalScaleModeFitCircle, DEG_TO_TRIGANGLE(360 - half), DEG_TO_TRIGANGLE(360));
  graphics_draw_arc(ctx, box, GOvalScaleModeFitCircle, 0, DEG_TO_TRIGANGLE(half));
}

// A simple face: eyes, and a mouth that curves with the mood.
static void draw_face(GContext *ctx, GRect box, uint16_t row, bool highlighted, void *context) {
  int mood = MOODS[row].value;
  GColor ink = highlighted ? GColorWhite : COLOR_ACCENT;
  GPoint centre = grect_center_point(&box);
  int r = box.size.w / 2 - 1;
  graphics_context_set_stroke_color(ctx, ink);
  graphics_context_set_fill_color(ctx, ink);
  graphics_context_set_stroke_width(ctx, 2);
  graphics_draw_circle(ctx, centre, r);
  graphics_fill_circle(ctx, GPoint(centre.x - r / 3, centre.y - r / 4), 2);
  graphics_fill_circle(ctx, GPoint(centre.x + r / 3, centre.y - r / 4), 2);
  int w = r;  // Mouth width.
  GRect mouth;
  switch (mood) {
    case 5:  // A big smile.
      mouth = GRect(centre.x - w / 2, centre.y - r / 4, w, r * 3 / 4);
      graphics_draw_arc(ctx, mouth, GOvalScaleModeFitCircle, DEG_TO_TRIGANGLE(100), DEG_TO_TRIGANGLE(260));
      break;
    case 4:  // A small smile.
      mouth = GRect(centre.x - w / 2, centre.y - r / 8, w, r / 2);
      graphics_draw_arc(ctx, mouth, GOvalScaleModeFitCircle, DEG_TO_TRIGANGLE(115), DEG_TO_TRIGANGLE(245));
      break;
    case 3:  // Straight.
      graphics_draw_line(ctx, GPoint(centre.x - w / 3, centre.y + r / 3), GPoint(centre.x + w / 3, centre.y + r / 3));
      break;
    case 2:  // A small frown.
      mouth = GRect(centre.x - w / 2, centre.y + r / 4, w, r / 2);
      draw_top_arc(ctx, mouth, 65);
      break;
    default:  // A frown.
      mouth = GRect(centre.x - w / 2, centre.y + r / 5, w, r * 3 / 4);
      draw_top_arc(ctx, mouth, 80);
      break;
  }
  graphics_context_set_stroke_width(ctx, 1);
}

void picker_mood(void) {
  push_choices(PBL_DISPLAY_WIDTH >= 180 ? "How are you feeling?" : "How do you feel?", &s_moods, draw_face);
}

// ---- Water and coffee ----

static const Choice WATERS[] = {
  { "A glass", "250 ml", 250 },
  { "A bottle", "500 ml", 500 },
};
static ChoiceList s_waters = { WATERS, ARRAY_LENGTH(WATERS), LOG_WATER, 0 };

void picker_water(void) {
  push_choices("Water", &s_waters, NULL);
}

static const Choice COFFEES[] = {
  { "A cup", "240 ml", 240 },
  { "A mug", "350 ml", 350 },
  { "A small cup", "120 ml", 120 },
};
static ChoiceList s_coffees = { COFFEES, ARRAY_LENGTH(COFFEES), LOG_COFFEE, 0 };

void picker_coffee(void) {
  push_choices("Coffee", &s_coffees, NULL);
}

// ---- Bladder and bowel: one list, three kinds ----

static const Choice BLADDER[] = {
  { "Toilet trip", NULL, LOG_TOILET },
  { "Leak", NULL, LOG_LEAK },
  { "Bowel movement", NULL, LOG_BOWEL },
};

static uint16_t bladder_count(void *context) {
  return ARRAY_LENGTH(BLADDER);
}

static void bladder_row(uint16_t row, const char **title, const char **subtitle, void *context) {
  *title = BLADDER[row].title;
  *subtitle = BLADDER[row].subtitle;
}

static void bladder_select(uint16_t row, void *context) {
  comms_send((LogKind)BLADDER[row].value, 0, 0, true);
}

void picker_bladder(void) {
  ListSpec spec = {
    .title = "Bladder & bowel",
    .count = bladder_count,
    .row = bladder_row,
    .select = bladder_select,
  };
  list_window_push(&spec);
}

// ---- Hot flash ----

static const Choice FLASHES[] = {
  { "Mild", NULL, 1 },
  { "Medium", NULL, 2 },
  { "Strong", NULL, 3 },
};
static ChoiceList s_flashes = { FLASHES, ARRAY_LENGTH(FLASHES), LOG_HOT_FLASH, 0 };

void picker_hot_flash(void) {
  push_choices("Hot flash", &s_flashes, NULL);
}

// ---- Symptoms: what, then how bad (kept in step with logic.js SYMPTOMS) ----

static const char *SYMPTOM_NAMES[] = { "Headache", "Pain", "Nausea", "Tiredness", "Dizziness", "Something else" };

static const Choice SEVERITIES[] = {
  { "1", "Barely there", 1 },
  { "2", "Mild", 2 },
  { "3", "Mild", 3 },
  { "4", "Moderate", 4 },
  { "5", "Moderate", 5 },
  { "6", "Moderate", 6 },
  { "7", "Strong", 7 },
  { "8", "Strong", 8 },
  { "9", "Severe", 9 },
  { "10", "The worst", 10 },
};
static ChoiceList s_severity = { SEVERITIES, ARRAY_LENGTH(SEVERITIES), LOG_SYMPTOM, 0 };

static uint16_t symptom_count(void *context) {
  return ARRAY_LENGTH(SYMPTOM_NAMES);
}

static void symptom_row(uint16_t row, const char **title, const char **subtitle, void *context) {
  *title = SYMPTOM_NAMES[row];
  *subtitle = NULL;
}

static void symptom_select(uint16_t row, void *context) {
  s_severity.index = row;
  push_choices("How bad, 1 to 10?", &s_severity, NULL);
}

void picker_symptom(void) {
  ListSpec spec = {
    .title = "Symptom",
    .count = symptom_count,
    .row = symptom_row,
    .select = symptom_select,
  };
  list_window_push(&spec);
}

// ---- Medications: today's doses; choosing one asks before marking it taken ----

static int s_dose;
static char s_dose_sub[MAX_ITEMS][40];

static uint16_t dose_count(void *context) {
  return g_data.dose_count ? g_data.dose_count : 1;
}

static void dose_row(uint16_t row, const char **title, const char **subtitle, void *context) {
  if (g_data.dose_count == 0) {
    *title = "Nothing due today";
    *subtitle = NULL;
    return;
  }
  Dose *dose = &g_data.doses[row];
  snprintf(s_dose_sub[row], sizeof(s_dose_sub[row]), "%s · %s", dose->time, dose->taken ? "Taken" : "To take");
  *title = dose->name;
  *subtitle = s_dose_sub[row];
}

static uint16_t confirm_count(void *context) {
  return 2;
}

static void confirm_row(uint16_t row, const char **title, const char **subtitle, void *context) {
  *title = row == 0 ? "Taken" : "Not yet";
  *subtitle = NULL;
}

static void confirm_select(uint16_t row, void *context) {
  window_stack_pop(true);
  if (row == 0) comms_send(LOG_DOSE_TAKEN, 0, s_dose, false);
}

static void dose_select(uint16_t row, void *context) {
  if (g_data.dose_count == 0) return;
  if (g_data.doses[row].taken) {
    toast_show("Already marked taken", true, 1200);
    return;
  }
  s_dose = row;
  ListSpec spec = {
    .title = g_data.doses[row].name,
    .count = confirm_count,
    .row = confirm_row,
    .select = confirm_select,
  };
  list_window_push(&spec);
}

void picker_doses(void) {
  ListSpec spec = {
    .title = "Medications",
    .count = dose_count,
    .row = dose_row,
    .select = dose_select,
  };
  list_window_push(&spec);
}

// ---- Habits: tick one off ----

static uint16_t habit_count(void *context) {
  return g_data.habit_count ? g_data.habit_count : 1;
}

static void habit_row(uint16_t row, const char **title, const char **subtitle, void *context) {
  if (g_data.habit_count == 0) {
    *title = "None for today";
    *subtitle = NULL;
    return;
  }
  *title = g_data.habits[row].name;
  *subtitle = g_data.habits[row].done ? "Done" : "Select to tick";
}

static void habit_select(uint16_t row, void *context) {
  if (g_data.habit_count == 0) return;
  if (g_data.habits[row].done) {
    toast_show("Already done today", true, 1200);
    return;
  }
  comms_send(LOG_HABIT, 1, row, false);
}

void picker_habits(void) {
  ListSpec spec = {
    .title = "Habits",
    .count = habit_count,
    .row = habit_row,
    .select = habit_select,
  };
  list_window_push(&spec);
}
