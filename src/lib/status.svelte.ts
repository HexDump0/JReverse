// The status bar's transient message and running task, shared by every view.

const MESSAGE_MS = 5000;

export const status = $state({ message: "", error: false, task: "" });

let timer: ReturnType<typeof setTimeout> | undefined;

export function say(message: string, error = false) {
  status.message = message;
  status.error = error;
  clearTimeout(timer);
  timer = setTimeout(() => {
    status.message = "";
    status.error = false;
  }, MESSAGE_MS);
}

export function setTask(text: string) {
  status.task = text;
}
