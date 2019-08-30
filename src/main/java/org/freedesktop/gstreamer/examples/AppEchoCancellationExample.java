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


import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.lowlevel.MainLoop;
import org.jcodec.codecs.wav.WavHeader;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 *
 * A simple example of transcoding an audio file using AppSink and AppSrc.
 *
 * Depending on which gstreamer plugins are installed, this example can decode
 * nearly any media type, likewise for encoding. Specify the input and output
 * file locations with the first two arguments.
 *
 * @author Kyle R Wenholz (http://krwenholz.com)
 */
public class AppEchoCancellationExample {

    private static Pipeline pipe;

    static byte[] readAudioFile(String path) throws IOException {
        File file = new File(path);
        byte[] bytes = Files.readAllBytes(file.toPath());
        WavHeader header = WavHeader.read(file);
        return Arrays.copyOfRange(bytes, header.dataOffset, bytes.length);
    }

    static void setupAppSource(AppSrc source, byte[] bytes) {
        source.set("emit-signals", true);
        source.connect(new AppSrc.NEED_DATA() {

            private final ByteBuffer bb = ByteBuffer.wrap(bytes);

            @Override
            public void needData(AppSrc elem, int size) {
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

    public static void main(String[] args) throws Exception {
        Gst.init();

        Registry.get().scanPath("/usr/local/lib/gstreamer-1.0/");

//        String speakerOutputLocation = "/home/ubuntu/vowel/resources/noodle.pcm";
        byte[] speakerData = Files.readAllBytes(Paths.get("/home/ubuntu/vowel/resources/noodle.wav"));
        byte[] microphoneData = Files.readAllBytes(Paths.get("/home/ubuntu/vowel/resources/out-without-aec.wav"));

//        byte[] speakerData = readAudioFile("/home/ubuntu/vowel/resources/noodle.wav");
//        byte[] microphoneData = readAudioFile("/home/ubuntu/vowel/resources/out-without-aec.wav");

        final MainLoop loop = new MainLoop();


        AppSrc speakerSource = (AppSrc)ElementFactory.make("appsrc", "speaker-source");
        Element speakerDecoder = ElementFactory.make("decodebin", "speaker-decoder");
        Element speakerConverter = ElementFactory.make("audioconvert", "speaker-converter");
        Element echoProbe = ElementFactory.make("webrtcechoprobe", "echo-probe");
        Element probeVolume = ElementFactory.make("volume", "probe-volume");
        probeVolume.set("volume", 0);
//        AppSink fakeSink = (AppSink)ElementFactory.make("appsink", "fake-sink");

        AppSrc microphoneSource = (AppSrc)ElementFactory.make("appsrc", "microphone-source");
        Element microphoneDecoder = ElementFactory.make("decodebin", "microphone-decoder");
        Element microphoneConverter = ElementFactory.make("audioconvert", "microphone-converter");
        Element echoDsp = ElementFactory.make("webrtcdsp", "echo-dsp");
        echoDsp.set("echo-cancel", true);
        echoDsp.set("echo-suppression-level", 1);
        echoDsp.set("probe", echoProbe.getName());

        Element mixerSink = ElementFactory.make("audiomixer", "audio-mixer");
        Element speakerSink = ElementFactory.make("autoaudiosink", "speaker-sink");

//        Caps srcCaps = Caps.fromString("audio/x-raw,rate=48000,channels=1,format=S16LE");
//        speakerSource.setCaps(srcCaps);
//        microphoneSource.setCaps(srcCaps);

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

        setupAppSource(speakerSource, speakerData);
        setupAppSource(microphoneSource, microphoneData);
//        setupAppSink(fakeSink, speakerOutputLocation);

        pipe.addMany(
                speakerSource, speakerDecoder, speakerConverter, echoProbe, probeVolume,
                microphoneSource, microphoneDecoder, microphoneConverter, echoDsp,
                mixerSink, speakerSink);

        // probe
        speakerSource.link(speakerDecoder);
        dynamicLinkDecoderAndConverter(speakerDecoder, speakerConverter);
        speakerConverter.link(echoProbe);
        echoProbe.link(probeVolume);

        // echo cancellation
        microphoneSource.link(microphoneDecoder);
        dynamicLinkDecoderAndConverter(microphoneDecoder, microphoneConverter);
        microphoneConverter.link(echoDsp);

        // mixer (aec need it works, maybe for synchronization purpose)
        probeVolume.link(mixerSink);
        echoDsp.link(mixerSink);
        mixerSink.link(speakerSink);


        System.out.println("Playing...");
        pipe.play();
        System.out.println("Running...");
        loop.run();
        System.out.println("Returned, stopping playback");
        pipe.stop();
        Gst.deinit();
        Gst.quit();

    }
}

