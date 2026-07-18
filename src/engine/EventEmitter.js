/**
 * EventEmitter
 * ------------
 * The messaging backbone of the engine.
 * Lets other code "subscribe" to named events, and lets the
 * engine "emit" those events to everyone subscribed.
 *
 * This is how the engine talks UP to the outside world
 * (later: to React) without knowing who is listening.
 */
export class EventEmitter {
  constructor() {
    // A place to remember who is listening to what.
    // Keys are event names ("started", "stopped"...).
    // Values are a Set of callback functions to run when that event fires.
    // A Map holds key→value pairs; a Set holds a list with no duplicates.
    this._listeners = new Map();
  }

  /**
   * Subscribe to an event.
   * @param {string} event - the event name, e.g. "started"
   * @param {function} callback - the function to run when it fires
   */
  on(event, callback) {
    // If nobody has ever listened to this event, make an empty Set for it.
    if (!this._listeners.has(event)) {
      this._listeners.set(event, new Set());
    }
    // Add this callback to the set of listeners for that event.
    this._listeners.get(event).add(callback);
  }

  /**
   * Unsubscribe from an event.
   * Important later: React must stop listening when a component
   * disappears, or we get memory leaks.
   */
  off(event, callback) {
    // The ?. means "only if this event has listeners" — avoids errors.
    this._listeners.get(event)?.delete(callback);
  }

  /**
   * Emit an event — tell everyone listening that it happened.
   * @param {string} event - the event name
   * @param  {...any} args - any data to pass to the listeners
   */
  emit(event, ...args) {
    // For every callback subscribed to this event, run it.
    this._listeners.get(event)?.forEach((callback) => {
      callback(...args);
    });
  }
}