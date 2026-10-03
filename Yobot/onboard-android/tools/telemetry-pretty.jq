# Presentation only: raw JSONL remains the recording format.
def fields: .payload | if type == "object" then . else {} end;
def event_label:
  if .kind == "log.info" then "INFO"
  elif (.kind == "log.warn" or .kind == "dropped") then "WARN"
  elif .kind == "log.error" then "ERROR"
  elif .kind == "HeardUtterance" then
    if (fields).isFinal == false then "PARTIAL" else "HEARD" end
  else .kind end;
def message:
  fields as $p |
  if (.kind == "log.info" or .kind == "log.warn" or .kind == "log.error") then
    ([$p.msg, $p.error] | map(select(. != null) | tostring) | join(" — ")) as $msg |
    if $msg == "" then (.payload | tostring) else $msg end
  elif .kind == "HeardUtterance" and $p.text != null then "“\($p.text)”"
  elif .kind == "dropped" then "Dropped events: queue=\($p.queue // "?"), sinks=\($p.sinks // "?")"
  else (.payload | tostring) end;
"\(.tsWallMs / 1000 | strflocaltime("%H:%M:%S")).\(.tsWallMs % 1000 | floor | tostring | "000" + . | .[-3:])  \(event_label)  \(.source)  \(message | gsub("[\r\n\t]+"; " "))"
