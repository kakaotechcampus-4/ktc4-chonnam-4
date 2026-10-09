// CloudFront Function(viewer-request). 화면 주소(/child/quiz/… 처럼 마지막 조각에 확장자가 없는 것)는 index.html 로 보낸다.
// 파일(/assets/index-abc.js, /favicon.svg)은 그대로 둔다. /api/* 는 이 함수가 붙지 않는다(edge 모듈의 default 동작에만 붙인다).
// 사용자 지정 오류 응답(403·404 → index.html)은 배포 전체에 걸려 /api 의 404 까지 바꾸므로 쓰지 않는다.
function handler(event) {
  var request = event.request;
  var uri = request.uri;
  var last = uri.substring(uri.lastIndexOf('/') + 1);
  if (last.indexOf('.') === -1) {
    request.uri = '/index.html';
  }
  return request;
}
