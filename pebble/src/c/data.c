#include "app.h"

AppData g_data;

void data_init(void) {
  memset(&g_data, 0, sizeof(g_data));
  g_data.connected = true;
  g_data.readiness = g_data.sleep = g_data.activity = -1;
  g_data.stress = -1;
}

// Copies up to [length] bytes of [src] into [dest], never cutting a UTF-8 character in half.
void copy_utf8(char *dest, size_t size, const char *src, size_t length) {
  if (size == 0) return;
  size_t n = length < size - 1 ? length : size - 1;
  // Back off to the start of a character if the cut falls inside one.
  while (n > 0 && n < length && (((uint8_t)src[n]) & 0xC0) == 0x80) n--;
  memcpy(dest, src, n);
  dest[n] = '\0';
}

// Finds the end of the current field (a tab, a line break or the end).
static const char *field_end(const char *p) {
  while (*p && *p != '\t' && *p != '\n') p++;
  return p;
}

// Lines of tab-separated fields: doses "time\tname\t0|1".
void data_parse_doses(const char *packed) {
  g_data.dose_count = 0;
  const char *p = packed;
  while (p && *p && g_data.dose_count < MAX_ITEMS) {
    Dose *dose = &g_data.doses[g_data.dose_count];
    const char *end = field_end(p);
    copy_utf8(dose->time, sizeof(dose->time), p, end - p);
    p = end;
    if (*p == '\t') p++;
    end = field_end(p);
    copy_utf8(dose->name, sizeof(dose->name), p, end - p);
    p = end;
    if (*p == '\t') p++;
    dose->taken = (*p == '1');
    while (*p && *p != '\n') p++;
    if (*p == '\n') p++;
    g_data.dose_count++;
  }
}

// Lines of "name\t0|1".
void data_parse_habits(const char *packed) {
  g_data.habit_count = 0;
  const char *p = packed;
  while (p && *p && g_data.habit_count < MAX_ITEMS) {
    HabitItem *habit = &g_data.habits[g_data.habit_count];
    const char *end = field_end(p);
    copy_utf8(habit->name, sizeof(habit->name), p, end - p);
    p = end;
    if (*p == '\t') p++;
    habit->done = (*p == '1');
    while (*p && *p != '\n') p++;
    if (*p == '\n') p++;
    g_data.habit_count++;
  }
}

// 12345 → "12,345".
void format_thousands(int value, char *buffer, size_t size) {
  if (value < 1000) {
    snprintf(buffer, size, "%d", value);
  } else if (value < 1000000) {
    snprintf(buffer, size, "%d,%03d", value / 1000, value % 1000);
  } else {
    snprintf(buffer, size, "%d,%03d,%03d", value / 1000000, (value / 1000) % 1000, value % 1000);
  }
}

// 1250 → "1.2" (litres, one decimal; there's no floating point formatting on the watch).
void format_litres(int ml, char *buffer, size_t size) {
  snprintf(buffer, size, "%d.%d", ml / 1000, (ml % 1000) / 100);
}

const char *stress_label(int stress) {
  switch (stress) {
    case 0: return "Stressed";
    case 1: return "Engaged";
    case 2: return "Relaxed";
    case 3: return "Restored";
    default: return NULL;
  }
}

int doses_left(void) {
  int left = 0;
  for (int i = 0; i < g_data.dose_count; i++) {
    if (!g_data.doses[i].taken) left++;
  }
  return left;
}

int habits_done(void) {
  int done = 0;
  for (int i = 0; i < g_data.habit_count; i++) {
    if (g_data.habits[i].done) done++;
  }
  return done;
}
