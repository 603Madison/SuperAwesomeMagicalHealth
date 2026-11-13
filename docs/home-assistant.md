# Home Assistant

Two ways in, and they work well together:

- **Sensors**: Home Assistant asks the server for today's numbers every few minutes.
- **Webhooks**: the server tells Home Assistant the moment something happens (a dose is due or
  missed, the night's sleep is in, a ring's battery is low).

## Sensors

In SuperAwesomeMagicalHealth, open **Settings › Integrations** and make a **Home Assistant key**.
It's shown once. It can read `/api/integrations/state` and nothing else; revoke it there (or under
your sign-ins) whenever you like.

Keep it in Home Assistant's `secrets.yaml`:

```yaml
samh_key: "Bearer paste-the-key-here"
```

Then in `configuration.yaml` (change the address to your server's):

```yaml
rest:
  - resource: https://health.example.com/api/integrations/state
    headers:
      Authorization: !secret samh_key
    scan_interval: 300
    sensor:
      - name: Readiness
        value_template: "{{ value_json.readiness }}"
        unit_of_measurement: ""
      - name: Sleep score
        value_template: "{{ value_json.sleep_score }}"
      - name: Steps today
        value_template: "{{ value_json.steps }}"
        unit_of_measurement: steps
      - name: Resting heart rate
        value_template: "{{ value_json.resting_hr }}"
        unit_of_measurement: bpm
      - name: Water today
        value_template: "{{ value_json.water_ml }}"
        unit_of_measurement: mL
      - name: Doses left today
        value_template: "{{ value_json.doses_left_today }}"
      - name: Next dose
        value_template: "{{ value_json.next_dose_at }}"
        device_class: timestamp
      - name: Lowest device battery
        value_template: "{{ value_json.device_battery_min }}"
        unit_of_measurement: "%"
        device_class: battery
```

Everything the state holds: `date`, `readiness`, `readiness_label`, `sleep_score`,
`activity_score`, `sleep_minutes`, `steps`, `step_goal`, `active_calories`, `resting_hr`, `hrv`,
`stress_state`, `water_ml`, `water_goal`, `weight_kg`, `doses_left_today`, `doses_taken_today`,
`next_dose_at`, `device_battery_min`, `devices` (each device's battery by name) and `updated_at`.
Values the server doesn't have are `null`. Medications appear only as counts and times, never
names. With water turned off (in Settings › Features, under Food), `water_ml` and `water_goal` are left out.

## Webhooks

In Home Assistant, make an automation with a **Webhook** trigger and note its ID. Then in
SuperAwesomeMagicalHealth, **Settings › Integrations › Add a webhook** with the address
`https://your-home-assistant:8123/api/webhook/<the ID>` and pick what to send. **Send a test**
checks it straight away; the list shows each hook's last call.

Each call is a `POST` with a JSON body:

```json
{
  "event": "dose.due",
  "at": 1794643200000,
  "user": "alex",
  "title": "Time for your 08:00 medication",
  "data": { "scheduled_for": "2026-11-12T08:00Z", "time": "08:00" }
}
```

Names (of medications, symptoms, appointments) are only included when the hook's **Include
names** is on. The events: `morning`, `readiness.low`, `dose.due`, `dose.missed`, `dose.taken`,
`refill`, `bp.high`, `glucose.low`, `glucose.high`, `migraine`, `habit.due`,
`appointment.soon`, `bladder.training`, `supplies.low`, `cycle.reminder`, `voice.practice`,
`device.battery_low`, `device.quiet`, `digest` and `test`.

An automation that flashes a lamp when a dose is due, and turns it back when it's taken:

```yaml
automation:
  - alias: Dose reminder light
    trigger:
      - platform: webhook
        webhook_id: samh-doses
        allowed_methods: [POST]
        local_only: true
    action:
      - choose:
          - conditions: "{{ trigger.json.event == 'dose.due' }}"
            sequence:
              - service: light.turn_on
                target: { entity_id: light.kitchen }
                data: { color_name: purple, flash: long }
          - conditions: "{{ trigger.json.event == 'dose.taken' }}"
            sequence:
              - service: light.turn_on
                target: { entity_id: light.kitchen }
                data: { color_temp_kelvin: 3000 }
```

### Checking a call is really from your server

Each call carries `X-SAMH-Signature: sha256=<hex>`: the HMAC-SHA256 of the raw body, keyed with
the hook's secret (shown once, when the hook is made). Home Assistant's webhook trigger doesn't
check signatures; anything else you point a hook at can.

Webhooks may call addresses on your own network (that's where Home Assistant usually is). An
admin can turn that off in **Admin › Settings** if the server is shared with people you don't
know.
