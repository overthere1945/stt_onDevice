# app/src/test/resources/yamnet — Yamnet 클래스 표(테스트 리소스)

change(add)-hyungchul-2026-10-10-15:43 (Task 16)

`yamnet_class_map.csv` 는 CSV 라 주석을 달 수 없으므로 이 문서가 설명을 대신한다. 직접 고치지 않는다.

- 용도: JVM 테스트 `YamnetVadTest.speechClassIndexIsZero` 가 읽어 "Speech" 행의 index 가 `YamnetVad.SPEECH_CLASS_INDEX`(0)와 같은지,
  행이 521 개(= 모델 출력 클래스 수)이고 index 가 0..520 순서인지 확인한다.
- 형식: 머리글 `index,mid,display_name`, 521 행. `display_name` 에 쉼표가 있으면 큰따옴표로 감싸져 있다. index 0 = `/m/09x0r,Speech`.
- 출처 URL: https://raw.githubusercontent.com/tensorflow/models/34a21326906b9574fa11c4d6d0a5c534ff039267/research/audioset/yamnet/yamnet_class_map.csv
  (tensorflow/models master 커밋 34a2132 기준, 이 파일의 마지막 변경 커밋 dfffd62 2019-11-21)
- SHA-256: `cdf24d193e196d9e95912a2667051ae203e92a2ba09449218ccb40ef787c6df2` (원본 그대로 복사, 수정 없음)
- 라이선스: **Apache-2.0** (tensorflow/models, Copyright Google LLC). 재배포 시 출처·라이선스 고지를 유지한다.
- 모델과의 대응: 앱이 쓰는 `yamnet.tflite`(Kaggle `google/yamnet` tfLite classification-tflite v1, SHA-256 `10c95ea3…05c317de`)에
  내장된 `yamnet_label_list.txt` 521 줄이 이 CSV 의 `display_name` 과 완전히 같음을 PC 에서 확인했다
  (`tools/golden/gen_yamnet.py` 가 매번 assert, 자세한 내용은 `tools/golden/README_한국어.md` 9 절).
