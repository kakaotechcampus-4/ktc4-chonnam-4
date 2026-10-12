package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.SpeechSynthesisRequest;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscription;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 음성 왕복 측정: 회귀 세트의 아동 발화를 TTS 로 합성하고, 그 음성을 다시 STT 로 전사한다.
 *
 * <p>TTS·STT 지연(타임아웃 근거)과 STT 신뢰도 분포(0.40 기준 근거)를 얻는다. 합성 음성은 또렷한 성인 목소리라 실제 아동 녹음보다 신뢰도가 높게 나온다. 이
 * 값은 상한으로 읽고, 실제 아동 녹음으로 다시 잰다.
 */
final class SpeechRoundTrip {

    private final RegressionSet set;
    private final TextToSpeechProvider tts;
    private final SpeechToTextProvider stt;

    SpeechRoundTrip(RegressionSet set, TextToSpeechProvider tts, SpeechToTextProvider stt) {
        this.set = Objects.requireNonNull(set);
        this.tts = Objects.requireNonNull(tts);
        this.stt = Objects.requireNonNull(stt);
    }

    List<Sample> run(int repeats, java.util.function.Predicate<RegressionSet.Case> filter) {
        List<Sample> samples = new ArrayList<>();
        for (int repeat = 1; repeat <= repeats; repeat++) {
            for (RegressionSet.Case turn : set.cases()) {
                if (!filter.test(turn)) continue;
                samples.add(roundTrip(turn, repeat));
            }
        }
        return samples;
    }

    private Sample roundTrip(RegressionSet.Case turn, int repeat) {
        AiTraceContext trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        null,
                        UUID.randomUUID(),
                        set.turnId(turn),
                        null,
                        null);
        long ttsStart = System.nanoTime();
        AiCallResult<SynthesizedSpeech> spoken =
                tts.synthesize(new SpeechSynthesisRequest(trace, 1, turn.utterance(), null, null));
        long ttsMs = (System.nanoTime() - ttsStart) / 1_000_000;
        if (!(spoken instanceof AiCallResult.Success<SynthesizedSpeech> speech)) {
            String failure =
                    ((AiCallResult.Failure<SynthesizedSpeech>) spoken).failure().type().name();
            return new Sample(turn.id(), repeat, ttsMs, 0, failure, null, null, null, null, null);
        }
        byte[] audio = speech.data().audio();
        long sttStart = System.nanoTime();
        AiCallResult<SpeechTranscription> heard =
                stt.transcribe(
                        new SpeechTranscriptionRequest(trace, 1, audio, speech.data().format()));
        long sttMs = (System.nanoTime() - sttStart) / 1_000_000;
        if (!(heard instanceof AiCallResult.Success<SpeechTranscription> transcription)) {
            String failure =
                    ((AiCallResult.Failure<SpeechTranscription>) heard).failure().type().name();
            return new Sample(
                    turn.id(), repeat, ttsMs, audio.length, null, sttMs, failure, null, null, null);
        }
        SpeechTranscription result = transcription.data();
        return new Sample(
                turn.id(),
                repeat,
                ttsMs,
                audio.length,
                null,
                sttMs,
                null,
                result.confidence(),
                result.text(),
                characterErrorRate(turn.utterance(), result.text()));
    }

    /** STT 신뢰도 기준값(기능 명세: 0.40 이하면 다시 말해 달라고 한다). */
    static final double LOW_CONFIDENCE = 0.40;

    static String markdown(String runId, String ttsModel, String sttModel, List<Sample> samples) {
        EvalSummary.Latency ttsLatency =
                EvalSummary.Latency.of(
                        samples.stream()
                                .filter(s -> s.ttsFailure() == null)
                                .map(Sample::ttsMs)
                                .toList());
        EvalSummary.Latency sttLatency =
                EvalSummary.Latency.of(
                        samples.stream()
                                .filter(s -> s.sttMs() != null && s.sttFailure() == null)
                                .map(Sample::sttMs)
                                .toList());
        List<Double> confidences =
                samples.stream().map(Sample::confidence).filter(Objects::nonNull).sorted().toList();
        long transcribed = samples.stream().filter(s -> s.transcript() != null).count();
        long nullConfidence =
                samples.stream()
                        .filter(s -> s.transcript() != null && s.confidence() == null)
                        .count();
        long low = confidences.stream().filter(c -> c <= LOW_CONFIDENCE).count();
        double meanCer =
                samples.stream()
                        .map(Sample::characterErrorRate)
                        .filter(Objects::nonNull)
                        .mapToDouble(Double::doubleValue)
                        .average()
                        .orElse(Double.NaN);

        StringBuilder md = new StringBuilder("# 음성 왕복 측정 — ").append(runId).append("\n\n");
        md.append("TTS `")
                .append(ttsModel)
                .append("` → STT `")
                .append(sttModel)
                .append("`, 회귀 세트 아동 발화 ")
                .append(samples.size())
                .append("건\n\n");
        md.append("| 지표 | 값 |\n| --- | --- |\n");
        md.append(
                "| TTS 지연 p50 / p95 / 최대 | %d / %d / %dms (%d건) |\n"
                        .formatted(
                                ttsLatency.p50(),
                                ttsLatency.p95(),
                                ttsLatency.max(),
                                ttsLatency.samples()));
        md.append(
                "| STT 지연 p50 / p95 / 최대 | %d / %d / %dms (%d건) |\n"
                        .formatted(
                                sttLatency.p50(),
                                sttLatency.p95(),
                                sttLatency.max(),
                                sttLatency.samples()));
        md.append("| TTS 제한 시간 제안 | %s |\n".formatted(ttsLatency.suggestedTimeout()));
        md.append("| STT 제한 시간 제안 | %s |\n".formatted(sttLatency.suggestedTimeout()));
        md.append(
                "| 신뢰도 최소 / 하위 10%% / 중앙값 | %s |\n"
                        .formatted(
                                confidences.isEmpty()
                                        ? "-"
                                        : "%.3f / %.3f / %.3f"
                                                .formatted(
                                                        confidences.getFirst(),
                                                        confidences.get(
                                                                (int)
                                                                        Math.floor(
                                                                                0.1
                                                                                        * (confidences
                                                                                                        .size()
                                                                                                - 1))),
                                                        confidences.get(
                                                                (confidences.size() - 1) / 2))));
        md.append("| 신뢰도 %.2f 이하 | %d / %d |\n".formatted(LOW_CONFIDENCE, low, confidences.size()));
        md.append("| 신뢰도 없음(null) | %d / %d |\n".formatted(nullConfidence, transcribed));
        md.append(
                "| 평균 글자 오류율(CER) | %s |\n"
                        .formatted(Double.isNaN(meanCer) ? "-" : "%.3f".formatted(meanCer)));
        md.append(
                "| TTS / STT 실패 | %d / %d |\n"
                        .formatted(
                                samples.stream().filter(s -> s.ttsFailure() != null).count(),
                                samples.stream().filter(s -> s.sttFailure() != null).count()));

        md.append(
                "\n| 케이스 | 회차 | TTS(ms) | STT(ms) | 신뢰도 | CER | 전사 |\n| --- | ---: | ---: | ---: | ---: | ---: | --- |\n");
        for (Sample s : samples) {
            md.append(
                    "| %s | %d | %d | %s | %s | %s | %s |\n"
                            .formatted(
                                    s.caseId(),
                                    s.repeat(),
                                    s.ttsMs(),
                                    s.sttMs() == null ? "-" : s.sttMs(),
                                    s.confidence() == null ? "-" : "%.3f".formatted(s.confidence()),
                                    s.characterErrorRate() == null
                                            ? "-"
                                            : "%.2f".formatted(s.characterErrorRate()),
                                    s.transcript() != null
                                            ? s.transcript()
                                            : nullToDash(s.ttsFailure(), s.sttFailure())));
        }
        md.append(
                "\n> 합성 음성(또렷한 성인 목소리)을 전사한 값이라 실제 아동 녹음보다 신뢰도가 높고 오류율이 낮게 나온다. 신뢰도 기준은 아동 녹음으로 다시 확인한다.\n"
                        + "> 제한 시간 제안 = max(p95 × 2, 최대 × 1.5) 를 초 단위로 올림. 표본이 적으면 참고값이다.\n");
        return md.toString();
    }

    private static String nullToDash(String ttsFailure, String sttFailure) {
        if (ttsFailure != null) return "TTS 실패 " + ttsFailure;
        if (sttFailure != null) return "STT 실패 " + sttFailure;
        return "-";
    }

    /** 공백·문장부호를 뺀 글자 단위 편집 거리 / 원문 글자 수. 0 이면 완전히 같다. */
    static double characterErrorRate(String expected, String actual) {
        String a = normalize(expected);
        String b = normalize(actual);
        if (a.isEmpty()) return b.isEmpty() ? 0.0 : 1.0;
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] =
                        Math.min(
                                Math.min(current[j - 1] + 1, previous[j] + 1),
                                previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return (double) previous[b.length()] / a.length();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\p{Punct}…·]", "");
    }

    /**
     * 왕복 한 번. sttMs 가 null 이면 TTS 단계에서 실패해 STT 를 부르지 않은 것이다. transcript 는 합성 문장을 전사한 결과라 아동 발화가
     * 아니다.
     */
    record Sample(
            String caseId,
            int repeat,
            long ttsMs,
            int audioBytes,
            String ttsFailure,
            Long sttMs,
            String sttFailure,
            Double confidence,
            String transcript,
            Double characterErrorRate) {}
}
