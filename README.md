# MP3 분할 (30분 단위)

Tag Editor와 같은 스타일로 만든 개인용 Android 앱입니다.

## 기능
- Download / Music 폴더에서 **30분 넘는 MP3**만 자동으로 찾아 목록에 표시
- 선택한 파일을 **30분 단위**로 자름 (예: 73분 → 30 + 30 + 13)
- 원본 파일이 있는 위치에 `분할 음원` 폴더를 만들고  
  `파일명_1.mp3`, `파일명_2.mp3` … 형태로 저장
- FFmpeg (`-c copy`)로 재인코딩 없이 빠르게 자름 (메타데이터 유지)

## 권한
- 음악 읽기 권한
- **모든 파일 액세스** (MANAGE_EXTERNAL_STORAGE) — 원본 옆 폴더에 쓰기 위해 필요

## 빌드
Android Studio에서 열고 그대로 실행하거나, GitHub Actions로 debug APK를 받을 수 있습니다.

```bash
./gradlew assembleDebug
```

## 패키지
`com.ss2ux.mp3split`
