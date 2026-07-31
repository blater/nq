# Format Scenario Files

These compact fixtures exercise scalar format handling across JSON, YAML, and XML readers.

- `format-scenarios.json`
- `format-scenarios.yaml`
- `format-scenarios.xml`

Each file uses the same `format_samples.scenario` shape and includes:

- integer, large integer, decimal, signed decimal, and zero number fields;
- date-only values;
- datetime values with minute, second, and millisecond resolution;
- datetime values with no timezone, `Z`, and numeric timezone offsets;
- time-only values with minute, second, and millisecond resolution;
- time-only values with no timezone, `Z`, and numeric timezone offsets.

YAML temporal values are quoted so the reader preserves the source lexical value instead of allowing YAML timestamp coercion.
