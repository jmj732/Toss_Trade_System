// node --test 는 .jsx 를 해석하지 못한다. JSX 를 기능 단위로 점진 도입하는 동안
// 테스트 러너가 해당 컴포넌트를 그대로 import 할 수 있도록 변환 훅을 등록한다.
import { register } from "node:module";

register("./jsx-hooks.mjs", import.meta.url);
