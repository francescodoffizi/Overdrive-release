class CabinAudioPlaybackProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this.buffer = new Float32Array(48000);
        this.readIndex = 0;
        this.writeIndex = 0;
        this.size = 0;
        this.primed = false;
        this.port.onmessage = (event) => {
            if (event.data && event.data.type === 'reset') {
                this.readIndex = 0;
                this.writeIndex = 0;
                this.size = 0;
                this.primed = false;
                return;
            }
            if (event.data instanceof ArrayBuffer) this.push(event.data);
        };
    }

    push(raw) {
        const pcm = new Int16Array(raw);
        const capacity = this.buffer.length;
        const start = Math.max(0, pcm.length - capacity);
        const incoming = pcm.length - start;
        const overflow = Math.max(0, this.size + incoming - capacity);
        this.readIndex = (this.readIndex + overflow) % capacity;
        this.size -= overflow;
        for (let i = start; i < pcm.length; i++) {
            this.buffer[this.writeIndex] = pcm[i] / 32768;
            this.writeIndex = (this.writeIndex + 1) % capacity;
            this.size++;
        }
    }

    process(inputs, outputs) {
        const channels = outputs[0];
        if (!channels || !channels.length) return true;
        const output = channels[0];
        if (!this.primed && this.size >= 3840) this.primed = true;
        for (let i = 0; i < output.length; i++) {
            if (this.primed && this.size > 0) {
                output[i] = this.buffer[this.readIndex];
                this.readIndex = (this.readIndex + 1) % this.buffer.length;
                this.size--;
            } else {
                output[i] = 0;
                if (this.size === 0) this.primed = false;
            }
        }
        for (let channel = 1; channel < channels.length; channel++) {
            channels[channel].set(output);
        }
        return true;
    }
}

registerProcessor('cabin-audio-playback', CabinAudioPlaybackProcessor);
