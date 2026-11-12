const source = document.querySelector('#playground-source');
const output = document.querySelector('#playground-output');
const status = document.querySelector('#playground-status');
const run = document.querySelector('#playground-run');
const stop = document.querySelector('#playground-stop');
const reset = document.querySelector('#playground-reset');
const original = source.value;
let worker;
let timer;

function finish(message) {
    clearTimeout(timer);
    worker?.terminate();
    worker = undefined;
    run.disabled = false;
    stop.disabled = true;
    source.readOnly = false;
    status.textContent = message;
}

function deadline(milliseconds, message) {
    clearTimeout(timer);
    timer = setTimeout(() => {
        output.textContent = message;
        finish('Stopped · time limit reached');
    }, milliseconds);
}

function execute() {
    if (worker) return;
    if (!source.value.trim()) {
        status.textContent = 'Add a Java example first.';
        source.focus();
        return;
    }
    run.disabled = true;
    stop.disabled = false;
    source.readOnly = true;
    output.textContent = '';
    status.textContent = 'Loading Java 21… First run may take a moment.';
    try {
        const active = new Worker(new URL('./worker.js', import.meta.url));
        worker = active;
        deadline(120_000, 'Java could not finish loading. Check your connection and try Run again.');
        active.onmessage = ({data}) => {
            if (worker !== active) return;
            if (data.type === 'compiling') {
                status.textContent = 'Compiling Playground.java…';
                deadline(60_000, 'Compilation took too long. Try a smaller example.');
            } else if (data.type === 'running') {
                status.textContent = 'Running…';
                deadline(10_000, 'Execution stopped after 10 seconds. Check for an infinite loop or reduce the work.');
            } else if (data.type === 'version') {
                document.querySelector('#playground-version').textContent = `Presence ${data.version} · Java 21`;
            } else if (data.type === 'done') {
                output.textContent = data.output || '(Program finished without printing output.)';
                finish(data.ok ? 'Finished' : data.compilation ? 'Compilation failed' : 'Program failed');
            } else if (data.type === 'error') {
                output.textContent = data.message;
                finish('Could not run · try again');
            }
        };
        active.onerror = () => {
            if (worker !== active) return;
            output.textContent = 'The Java worker could not start or stopped unexpectedly. Check your connection and allow cjrtnc.leaningtech.com, then try Run again.';
            finish('Could not run · try again');
        };
        active.postMessage({source:source.value});
    } catch (error) {
        output.textContent = `Could not start the playground: ${error.message}`;
        finish('Could not start');
    }
}

run.addEventListener('click', execute);
stop.addEventListener('click', () => {
    output.textContent = 'Stopped. Edit the example or Run again to start fresh.';
    finish('Stopped');
});
reset.addEventListener('click', () => {
    finish('Ready');
    source.value = original;
    output.textContent = 'Your output will appear here.';
    source.focus();
});
source.addEventListener('keydown', event => {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
        event.preventDefault();
        execute();
    }
});
window.addEventListener('pagehide', () => { clearTimeout(timer); worker?.terminate(); });
run.disabled = false;
reset.disabled = false;
status.textContent = 'Ready';
