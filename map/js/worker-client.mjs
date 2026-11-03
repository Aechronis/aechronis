// One request table per worker; errors reject callers instead of leaving loading stuck.
export class WorkerClient {
  constructor(worker) {
    this.worker = worker;
    this.pending = new Map();
    this.sequence = 0;
    worker.onmessage = ({ data }) => {
      if (!data || !Number.isSafeInteger(data.id)) {
        this.fail(new Error('Map worker returned an invalid response'));
        return;
      }
      const { id, result, error } = data;
      const request = this.pending.get(id);
      if (!request) return;
      this.pending.delete(id);
      if (error) request.reject(new Error(error));
      else if (result === undefined) request.reject(new Error('Map worker returned no result'));
      else request.resolve(result);
    };
    worker.onerror = event => this.fail(new Error(event.message || 'Map worker failed'));
    worker.onmessageerror = () => this.fail(new Error('Map worker response could not be decoded'));
  }
  fail(error) {
    this.error = error;
    for (const request of this.pending.values()) request.reject(error);
    this.pending.clear();
  }
  call(method, args, transfer = []) {
    if (this.error) return Promise.reject(this.error);
    return new Promise((resolve, reject) => {
      const id = ++this.sequence;
      this.pending.set(id, { resolve, reject });
      try { this.worker.postMessage({ id, method, args }, transfer); }
      catch (error) { this.pending.delete(id); reject(error); }
    });
  }
}
