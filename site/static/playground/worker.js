// A disposable worker owns each JVM. Stop/Reset can terminate it even during an infinite loop.
self.onmessage = async ({data}) => {
    self.onmessage = null;
    try {
        if (typeof data.source !== 'string' || data.source.length > 32_768) {
            throw new Error('Keep the example below 32,768 characters.');
        }
        const manifestUrl = new URL('./lib/manifest.json', self.location.href);
        const response = await fetch(manifestUrl, {cache:'no-store'});
        if (!response.ok) throw new Error('Could not load the Presence build manifest. Try again after refreshing.');
        const manifest = await response.json();
        if (!/^presence-[a-f0-9]{16}\.jar$/.test(manifest.library)
                || !/^playground-[a-f0-9]{16}\.jar$/.test(manifest.runner)) {
            throw new Error('The playground build manifest is invalid.');
        }
        const libraryPath = '/app' + new URL(manifest.library, manifestUrl).pathname;
        const runnerPath = '/app' + new URL(manifest.runner, manifestUrl).pathname;
        postMessage({type:'version',version:manifest.version});
        // Community runtime stays on the vendor CDN. Do not bundle it in Presence releases.
        importScripts('https://cjrtnc.leaningtech.com/5.0/loader.js');
        await cheerpjInit({version:21,status:'none'});
        const lib = await cheerpjRunLibrary(`${libraryPath}:${runnerPath}`);
        const Runner = await lib.io.transductive.presence.site.PlaygroundRunner;
        postMessage({type:'compiling'});
        const diagnostics = await Runner.compile(data.source, libraryPath);
        if (diagnostics) {
            postMessage({type:'done',ok:false,compilation:true,output:diagnostics});
            return;
        }
        postMessage({type:'running'});
        const result = JSON.parse(await Runner.run());
        postMessage({type:'done',...result});
    } catch (error) {
        let message;
        try { message = error.getMessage ? await error.getMessage() : error.message || String(error); }
        catch { message = 'The Java runtime stopped unexpectedly.'; }
        postMessage({type:'error',message});
    }
};
