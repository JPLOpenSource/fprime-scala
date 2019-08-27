```
@startuml
skinparam stateFontSize 30
  skinparam stateAttributeFontSize 30
  skinparam arrowFontSize 30
  skinparam classFontSize 30
  skinparam defaultFontSize 30

  state off
  state on {
    state saving
    state exposing {
      state exposing_light
      state exposing_dark
      [*] --> exposing_light
    }
    state powering
    [*] --> powering
  }
  [*] --> off

  note "var duration: Int = 0\nval DARK_THRESHOLD = 5" as VARS

  off : entry{MissedEvents.submit()}
  exposing_light : entry{o_cam.invoke(Open); setTimer(duration)}
  exposing_light : exit{o_cam.invoke(Close)}
  exposing_dark : entry{setTimer(duration)}
  saving : entry{o_cam.invoke(SaveData)}

  off -d-> on : TakeImage(d)/\no_obs.logEvent(EvrTakeImage(d))\nduration = d\no_cam.invoke(PowerOn)
  on -u-> off : ShutDown/\no_obs.logEvent(EvrImageAborted)\no_cam.invoke(PowerOff)
  powering --> exposing : Ready
  exposing_light --> exposing_dark : ReceiveTimeout if getTemp() >= DARK_THRESHOLD
  exposing_light --> saving : ReceiveTimeout if getTemp() < DARK_THRESHOLD
  exposing_dark --> saving : ReceiveTimeout
  saving --> off : Ready/\no_obs.logEvent(EvrImageSaved)\no_cam.invoke(PowerOff)
@enduml
```