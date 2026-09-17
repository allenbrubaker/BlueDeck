# Tasker API (v1)

BlueDeck 1.13.0 adds request/result broadcasts for Tasker. Tasker owns the
proximity logic, phone GPS sampling, retry-once policy, and failure notification;
BlueDeck calls the vehicle API using the account and vehicle selected in the app.

## Sending requests

In Tasker, use **Misc → Send Intent**:

| Field | Value |
| --- | --- |
| Action | One of the full action strings below |
| Package | `com.bluedeck` (release) or `com.bluedeck.debug` (debug APK) |
| Class | `com.bluedeck.tasker.TaskerReceiver` |
| Target | Broadcast Receiver |
| Extra | `requestId:trip-42` (use a different ID for each logical request) |
| Cat, Data, MIME type | Leave empty |

| Request action | Result action | Successful result extras |
| --- | --- | --- |
| `com.bluedeck.tasker.GET_LOCATION` | `com.bluedeck.tasker.LOCATION_RESULT` | `latitude`, `longitude`, `speed`, `speedUnit`, `heading` |
| `com.bluedeck.tasker.GET_STATUS` | `com.bluedeck.tasker.STATUS_RESULT` | `locked`, `forceRefresh` |
| `com.bluedeck.tasker.LOCK` | `com.bluedeck.tasker.COMMAND_RESULT` | `command=LOCK`, `accepted=true` |
| `com.bluedeck.tasker.UNLOCK` | `com.bluedeck.tasker.COMMAND_RESULT` | `command=UNLOCK`, `accepted=true` |

`GET_STATUS` requests a refresh from the provider by default. Add
`forceRefresh:false` to request its cached status instead. Tasker automatically
types numeric extras; numeric request IDs are accepted and echoed as strings.
Without `requestId`, BlueDeck generates a UUID and includes it in the result.

Existing `com.bluedeck.widget.LOCK` / `com.bluedeck.widget.UNLOCK` intents directed
to `com.bluedeck.widget.VehicleWidgetProvider` can receive the new command result
by adding a `requestId` extra. Untagged widget actions keep their existing behavior.
The new Tasker receiver also accepts these two legacy action names.

## Receiving results

Create a Tasker profile using **Event → System → Intent Received** with one of the
result actions above. Leave categories, scheme and MIME type empty. Enable this
profile before sending a request. Results target the Tasker package
`net.dinglisch.android.taskerm`, without specifying a receiver component.

Every result includes:

| Extra | Type | Tasker variable | Meaning |
| --- | --- | --- | --- |
| `requestId` | String | `%requestid` | Correlates the result to the request |
| `action` | String | `%action` | `GET_LOCATION`, `GET_STATUS`, `LOCK`, or `UNLOCK` |
| `success` | Boolean | `%success` | `true` on a usable API result, otherwise `false` |
| `error` | String | `%error` | Error text, empty on success |
| `errorCode` | String | `%errorcode` | Machine-readable failure code, empty on success |
| `apiVersion` | Integer | `%apiversion` | `1` |
| `requestedAt` | Long | `%requestedat` | Time BlueDeck received the request, Unix milliseconds |
| `completedAt` | Long | `%completedat` | Time BlueDeck sent the result, Unix milliseconds |

Successful results also include `demo` (`%demo`). Ignore `demo=true` for real-vehicle
automation; demo results are simulated. Command results always contain `command`
and `accepted`, including on failure. `accepted=false` does **not** prove that a
timed-out/interrupted command had no effect.

Latitude and longitude are decimal degrees. Heading is degrees, with `0` a valid
north heading. Speed is the numeric value from the vehicle API; `speedUnit` is
the raw provider unit code, without assuming a unit conversion. Missing speed,
unit, or heading is an empty string, never an invented zero. Missing/invalid
coordinates or an absent/unrecognized lock state produces a failure result with
no location/lock fields. Check `success` before consuming those fields.

`GET_LOCATION` uses the Hyundai vehicle-location endpoint, never phone GPS or a
widget snapshot. The endpoint currently supports USA Hyundai (and demo mode);
other regions return the repository's unsupported-location error. Provider data
can lag: `completedAt` is **not** the GPS measurement time or proof of a fresh fix.
Likewise, a successful command means the API accepted it; use `GET_STATUS` to
observe the lock state afterward.

## Timing and retries

The receiver enqueues expedited WorkManager work so network calls can outlive a
broadcast receiver. Android may defer work. BlueDeck expires requests after 60
seconds and bounds execution to 45 seconds or the remaining lifetime, whichever
is shorter. Expired queued requests never send a vehicle command. Duplicate
in-flight requests with the same action and ID are coalesced.

Tasker should set its own result timeout (for example, 75 seconds), match the
request ID, and ignore late results from earlier attempts. A queued result may
arrive after that timeout if Android postponed execution. BlueDeck does not
retry commands automatically, including after an interrupted worker. Tasker can
perform the agreed single retry and then notify on failure. Its 4-second phone
location sampling loop should not poll the vehicle API every 4 seconds.

Failure codes: `VEHICLE_UNAVAILABLE`, `LOCATION_UNAVAILABLE`, `STATUS_UNAVAILABLE`,
`API_ERROR`, `TIMEOUT`, `EXPIRED`, `INTERRUPTED`, `ENQUEUE_FAILED`, `INTERNAL_ERROR`.

## v1 behavior and installation

No BlueDeck security token is required. LOCK and UNLOCK do not query or gate on
ignition, windows, doors, trunk, hood, climate, or current lock state. Normal
provider authentication/PIN requirements still apply. The exported receiver can
be invoked by other installed apps; result broadcasts are restricted to Tasker.
No credentials, VIN, or tokens are included in normal result fields.

The fork must be built and installed before these actions exist on the phone.
Android CI runs unit tests and produces the `BlueDeck-Tasker-debug` artifact.
The debug build uses `com.bluedeck.debug`, can coexist with the upstream release,
and needs its own sign-in and vehicle selection. The Java class and intent action
names stay `com.bluedeck...`. This change does not create a signed release or
Tasker XML profile.

## Validation

Run `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
Tests cover payload deserialization, unknown telemetry, real zero values,
command failures, no status pre-checks, timeouts, expired commands, correlation,
legacy action mapping, typed Android extras, and Tasker-only response routing.
Before enabling automatic lock/unlock, verify the request/result profiles on the
device in demo mode, then test real location/status and deliberate commands.

Protocol references: [Tasker intents](https://tasker.joaoapps.com/userguide/en/intents.html),
[Android broadcasts](https://developer.android.com/develop/background-work/background-tasks/broadcasts),
[expedited WorkManager requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).
