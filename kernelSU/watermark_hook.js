/* PD2405 live-library-only first-render ZEISS border focal correction. */
const vaf = Process.getModuleByName('libvivo.vaf.system.so');
const frame = vaf.enumerateExports().find(s =>
    s.name.includes('WaterMarkFrameParameterManager22fillParameterEvryFrame'));
const metadata = Process.getModuleByName('libvivo.algo.metadata.so');
const getter = metadata.enumerateExports().find(s =>
    s.name.includes('VMetadata21getMetadataValueByTag'));
if (!frame || !getter) throw new Error('Verified watermark symbols are missing');

let enabledUntil = 0;
let changedCount = 0;
const frameDepth = new Map();

function nominalFocal(stockMm, frameZoom) {
    const fixedSteps = [[135, 320], [170, 400], [230, 540], [340, 800],
        [1362, 3200]];
    for (const [source, label] of fixedSteps) {
        if (Math.abs(stockMm - source) <= 0.25) return label;
    }
    if (Number.isFinite(frameZoom) && frameZoom >= 3.7 && frameZoom <= 100) {
        const expectedStock = 85 * frameZoom / 3.7;
        if (Math.abs(stockMm - expectedStock) <= Math.max(2, expectedStock * 0.005)) {
            // The JPEG's DigitalZoomRatio stores two decimal places. Match that
            // per-frame value so EXIF and the first ZEISS render round alike.
            const savedZoom = Math.floor(frameZoom * 100 + 0.0001) / 100;
            return Math.round(200 * savedZoom / 3.7);
        }
    }
    return Math.round(stockMm * 2.35);
}

function acceptState() {
    recv('state', message => {
        const state = message.payload || {};
        enabledUntil = state.enabled === true ? Date.now() + 700 : 0;
        acceptState();
    });
}
acceptState();

Interceptor.attach(frame.address, {
    onEnter() {
        const tid = Process.getCurrentThreadId();
        frameDepth.set(tid, (frameDepth.get(tid) || 0) + 1);
    },
    onLeave() {
        const tid = Process.getCurrentThreadId();
        const depth = frameDepth.get(tid) || 0;
        if (depth <= 1) frameDepth.delete(tid);
        else frameDepth.set(tid, depth - 1);
    }
});

Interceptor.attach(getter.address, {
    onEnter(args) {
        this.valid = Date.now() < enabledUntil &&
            frameDepth.has(Process.getCurrentThreadId()) &&
            args[1].toUInt32() === 0x30075 &&
            args[3].toUInt32() === 0x98;
        if (this.valid) this.dest = args[2];
    },
    onLeave(result) {
        if (!this.valid || result.toInt32() !== 0 || Date.now() >= enabledUntil) return;
        try {
            const focal = this.dest.add(28).readFloat();
            const frameZoom = this.dest.add(24).readFloat();
            const physical = this.dest.add(36).readFloat();
            if (!Number.isFinite(focal) || focal < 84 || focal > 2300 ||
                !Number.isFinite(physical) || Math.abs(physical - 22.48) > 0.2) return;
            const target = nominalFocal(focal, frameZoom);
            if (target < 200 || target > 5400 || Math.abs(target - focal) < 0.5) return;
            this.dest.add(28).writeFloat(target);
            if (changedCount++ < 20) send({kind:'changed', focal, frameZoom, target, physical});
        } catch (error) {
            if (changedCount++ < 20) send({kind:'error', error:String(error)});
        }
    }
});
send({kind:'ready', pid:Process.id});
