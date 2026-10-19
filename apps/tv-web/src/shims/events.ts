// Node's EventEmitter for libraries pulled in by GramJS (socks, node-localstorage); the npm `events` package is the browser port.
import EventEmitter from "events/events.js";

export { EventEmitter };
export default EventEmitter;
