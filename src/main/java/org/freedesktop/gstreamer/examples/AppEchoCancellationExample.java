/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2018 Kyle R Wenholz.
 *
 * Copying and distribution of this file, with or without modification,
 * are permitted in any medium without royalty provided the copyright
 * notice and this notice are preserved.    This file is offered as-is,
 * without any warranty.
 *
 */
package org.freedesktop.gstreamer.examples;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import org.apache.commons.io.IOUtils;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.lowlevel.MainLoop;
import org.jcodec.codecs.wav.WavHeader;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class AppEchoCancellationExample {

    static byte[] readAudioFile(String path) throws IOException {
        File file = new File(path);
        byte[] bytes = Files.readAllBytes(file.toPath());
        WavHeader header = WavHeader.read(file);
        return Arrays.copyOfRange(bytes, header.dataOffset, bytes.length);
    }

    static void setupAppSource(AppSrc source, byte[] bytes) {
        source.set("emit-signals", true);
        source.connect(new AppSrc.NEED_DATA() {

            ByteBuffer bb = ByteBuffer.wrap(bytes);

            @Override
            public void needData(AppSrc elem, int size) {
                // infinite repeating
                if (bb.hasRemaining()) {
                    System.out.println("needData: size = " + size);
                    byte[] tempBuffer;
                    Buffer buf;
                    int copyLength = Math.min(bb.remaining(), size);
                    tempBuffer = new byte[copyLength];
                    buf = new Buffer(copyLength);
                    bb.get(tempBuffer);
                    System.out.println("Temp Buffer remaining bytes: " + bb.remaining());
                    buf.map(true).put(ByteBuffer.wrap(tempBuffer));
                    elem.pushBuffer(buf);
                } else {
                    elem.endOfStream();
                }
            }
        });
    }

    public static void setupAppSink(AppSink sink, String outputPath) throws FileNotFoundException {
        FileChannel output = new FileOutputStream(outputPath).getChannel();
        setupAppSink(sink, byteBuffer -> {
            try {
                System.out.println(String.format("AppSink[%s] received: %d bytes", sink.getName(), byteBuffer.remaining()));
                output.write(byteBuffer);
            } catch (IOException e) {
                System.err.println(e);
            }
        });
    }

    public static void setupAppSink(AppSink sink, Consumer<ByteBuffer> consumer) {
        // We connect to NEW_SAMPLE and NEW_PREROLL because either can come up
        // as sources of data, although usually just one does.
        sink.set("emit-signals", true);
        // sync=false lets us run the pipeline faster than real (based on the file) time
        // sink.set("sync", false);
        sink.connect((AppSink.NEW_SAMPLE) elem -> {
            Sample sample = elem.pullSample();
            ByteBuffer bytes = sample.getBuffer().map(false);
            try {
                consumer.accept(bytes);
            } catch (Exception e) {
                System.err.println(e);
            }
            sample.dispose();
            return FlowReturn.OK;
        });

        sink.connect((AppSink.NEW_PREROLL) elem -> {
            Sample sample = elem.pullPreroll();
            ByteBuffer bytes = sample.getBuffer().map(false);
            try {
                consumer.accept(bytes);
            } catch (Exception e) {
                System.err.println(e);
            }
            sample.dispose();
            return FlowReturn.OK;
        });
    }

    public static void dynamicLinkDecoderAndConverter(Element decoder, Element converter) {
        decoder.connect((Element.PAD_ADDED) (element, pad) -> {
            System.out.println("Dynamic pad created, linking decoder/converter");
            System.out.println("Pad name: " + pad.getName());
            System.out.println("Pad type: " + pad.getTypeName());
            Pad sinkPad = converter.getStaticPad("sink");
            try {
                pad.link(sinkPad);
                System.out.println("Pad linked.");
            } catch (PadLinkException ex) {
                System.out.println("Pad link failed : " + ex.getLinkResult());
            }
        });
    }

    static Element createAppSrc(Pipeline pipeline, String name, byte[] data) {
        AppSrc appSrc = (AppSrc) makeElement(pipeline, "appsrc", name);
        Element decoder = makeElement(pipeline, "decodebin", String.format("%s-decoder", name));
        Element converter = makeElement(pipeline, "audioconvert", String.format("%s-speaker-converter", name));
        setupAppSource(appSrc, data);
        appSrc.link(decoder);
        dynamicLinkDecoderAndConverter(decoder, converter);
        return converter;
    }

    static Element makeElement(Pipeline pipeline, String factoryName, String name) {
        Element element = ElementFactory.make(factoryName, name);
        pipeline.add(element);
        return element;
    }

    static Element link(Element ...elements) {
        for (int i=0; i<elements.length-1; i++) {
            elements[i].link(elements[i+1]);
        }
        return elements[elements.length-1];
    }

    static boolean outputToSpeaker = false;

    public static void main(String[] args) throws Exception {
        Gst.init();

        Registry.get().scanPath("/usr/local/lib/gstreamer-1.0/");

        List<byte[]> remoteParticipants = IntStream.range(0, 4)
                .mapToObj(i -> String.format("/amicorpus/ES2002a/audio/ES2002a.Headset-%d.wav", i))
                .map(location -> {
                    try (InputStream inputStream = AppEchoCancellationExample.class.getResourceAsStream(location)) {
                        return IOUtils.toByteArray(inputStream);
                    } catch (IOException e) {
                        e.printStackTrace();
                        throw new IllegalStateException();
                    }
                }).collect(Collectors.toList());

        byte[] localParticipant = Files.readAllBytes(Paths.get("/home/ubuntu/vowel/resources/noodle.wav"));

//        String speakerOutputLocation = "/home/ubuntu/vowel/resources/noodle.pcm";
//        byte[] microphoneData = Files.readAllBytes(Paths.get("/home/ubuntu/vowel/resources/out-without-aec.wav"));
//        byte[] speakerData = readAudioFile("/home/ubuntu/vowel/resources/noodle.wav");
//        byte[] microphoneData = readAudioFile("/home/ubuntu/vowel/resources/out-without-aec.wav");

        final MainLoop loop = new MainLoop();

        Pipeline pipe = new Pipeline();
        // We connect to EOS and ERROR on the bus for cleanup and error messages.
        Bus bus = pipe.getBus();
        bus.connect((Bus.EOS) source1 -> {
            System.out.println("Reached end of stream");
            loop.quit();
        });

        bus.connect((Bus.ERROR) (source12, code, message) -> {
            System.out.println("Error detected");
            System.out.println("Error source: " + source12.getName());
            System.out.println("Error code: " + code);
            System.out.println("Message: " + message);
            loop.quit();
        });

        List<Element> remoteParticipantTees = Streams
                .mapWithIndex(remoteParticipants.stream(), (data, idx) -> {
                    Element appSrc = createAppSrc(pipe, String.format("remote-participant-src-%d", idx), data);
                    // keep sample rate the same
                    Element resample = makeElement(pipe, "audioresample", String.format("resample-%d", idx));
                    Caps caps = Caps.fromString("audio/x-raw, rate=48000");
                    // this ami data has very low volume
                    Element amplifier = makeElement(pipe, "audioamplify", String.format("audioamplify-%d", idx));
                    amplifier.set("amplification", 5.0);
                    Element tee = makeElement(pipe, "tee", String.format("remote-participant-tee-%d", idx));
                    appSrc.link(resample);
                    resample.linkFiltered(amplifier, caps);
                    link(amplifier, tee);
                    return tee;
                })
                .collect(Collectors.toList());

        // mix participants & delay
        Element remoteParticipantsMixer = makeElement(pipe, "audiomixer", "remote-participants-mixer");
        Streams.mapWithIndex(remoteParticipantTees.stream(), (tee, idx) -> {
            Element queue = makeElement(pipe, "queue", String.format("mix-queue-%d", idx));
            tee.link(queue);
            return queue;
        }).forEach(queue -> queue.link(remoteParticipantsMixer));
        Element remoteParticipantsMixerDelay = makeElement(pipe, "entransshift", "participants-mixer-delay");
        remoteParticipantsMixerDelay.set("delay", 500);
        remoteParticipantsMixerDelay.set("running-time", false);
        remoteParticipantsMixer.link(remoteParticipantsMixerDelay);

        // create simulated microphone input
        Element localParticipantSource = createAppSrc(pipe, "local-participant", localParticipant);
        Element localParticipantVolume = makeElement(pipe, "volume", "local-participant-volume");
        localParticipantVolume.set("volume", 0.3); // noodle is too loud
        Element simulateMicrophoneMixer = makeElement(pipe, "audiomixer", "simulated-microphone-mixer");
        remoteParticipantsMixerDelay.link(simulateMicrophoneMixer);
        link(localParticipantSource, localParticipantVolume, simulateMicrophoneMixer);

        // probe
        List<Element> probes = Streams.mapWithIndex(remoteParticipantTees.stream(), (tee, idx) -> {
            // keep sample rate the same
            Element queue = makeElement(pipe, "queue", String.format("queue-sync-%d", idx));
            Element probe = makeElement(pipe, "webrtcechoprobe", String.format("echo-probe-%d", idx));
            return link(tee, queue, probe);
        }).collect(Collectors.toList());

        // add webrtc dsp
        List<Element> dsps = Streams.mapWithIndex(probes.stream(), (probe, idx) -> {
            Element echoDsp = makeElement(pipe, "webrtcdsp", String.format("echo-dsp-%d", idx));
            echoDsp.set("echo-cancel", true);
            echoDsp.set("echo-suppression-level", 1);
            echoDsp.set("probe", probe.getName());
            return echoDsp;
        }).collect(Collectors.toList());
        Element dspResult = link(ImmutableList.builder().add(simulateMicrophoneMixer).addAll(dsps).build().toArray(Element[]::new));

        Element finalMixer = makeElement(pipe, "audiomixer", "final-mixer");
        Streams.mapWithIndex(probes.stream(), (probe, idx) -> {
            Element volume = makeElement(pipe, "volume", String.format("volume-%d", idx));
            volume.set("volume", 0);
            return link(probe, volume);
        }).forEach(muted -> muted.link(finalMixer));
        dspResult.link(finalMixer);

        if (outputToSpeaker) {
            Element speakerSink = makeElement(pipe, "autoaudiosink", "speaker-sink");
            finalMixer.link(speakerSink);
        } else {
            Element encoder = makeElement(pipe, "wavenc", "wav enc");
            AppSink appSink = (AppSink)makeElement(pipe, "appsink", "audio-output");
            appSink.set("sync", false);
            setupAppSink(appSink, "out.wav");
            link(finalMixer, encoder, appSink);
        }

        long start = System.currentTimeMillis();

        System.out.println("Playing...");
        pipe.play();
        System.out.println("Running...");
        loop.run();
        System.out.println("Returned, stopping playback");
        pipe.stop();
        Gst.deinit();
        Gst.quit();

        long end = System.currentTimeMillis();
        System.out.println(String.format("Runtime: %d", end - start));

    }
}

