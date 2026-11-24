# StockGame

마인크래프트 주식 미니게임입니다. NPC 기능을 사용하려면 Citizens를 함께 설치하세요.

[다운로드](https://github.com/beddeak/StockGame/releases/latest)

## 설치 및 설정

Paper 1.20.6 · Java 21. 새 서버는 서버팩 ZIP을 빈 폴더에 풀고 Windows에서 `start.bat`을 실행하세요. 첫 실행 후 생성된 `eula.txt`를 읽고 동의하면 값을 변경한 뒤 다시 실행하세요. 기존 서버는 정지 후 `StockGame-1.0.0.jar`을 `plugins/`에 넣고 다시 시작하세요.

첫 실행 후 `plugins/StockGame/config.yml`을 편집하세요. 서버를 정상 종료하고 편집한 뒤 다시 시작하면 새 설정을 읽습니다. [기본 설정과 항목별 설명](src/main/resources/config.yml)

플레이어형 거래원은 Citizens가 필요합니다. 회사·스킨은 `stocks.yml`, 뉴스 확률과 효과는 `config.yml` 및 `news.yml`에서 조정합니다. 장이 열려 있을 때 `/stock news random 새벽제분`으로 확인하세요.

## 명령어

| 명령 | 권한 | 동작 |
|---|---|---|
| `/stock` | 모두 | 거래 메뉴 |
| `/stock join` | 모두 | 거래소 이동 |
| `/stock portfolio` | 모두 | 전체 보유 자산 |
| `/stock loan` | 모두 | 기본 300,000원 대출 요청 |
| `/stock loan 100000` | 모두 | 금액을 지정해 대출 요청 |
| `/stock repay 10000` / `/stock repay all` | 모두 | 지정 금액 또는 현재 현금으로 가능한 최대 금액 상환 |
| `/stock debt` | 모두 | 원금·이자·만기·파산 상태 확인 |
| `/stock pay 친구닉네임 10000` | 모두 | 온라인 플레이어에게 수수료 없이 송금 |
| `/stock work` | 모두 | 같은 거래소 월드의 채굴장으로 이동·없는 기본 도구 지급 |
| `/stock fish` | 모두 | 같은 월드의 낚시터로 이동·없는 기본 도구 지급 |
| `/stock parkour` | 모두 | 점프맵 시작 또는 처음부터 재도전 |
| `/stock parkour leave` | 모두 | 점프맵 도전 중단·거래소 스폰으로 이동 |
| `/stock workkit` | 모두 | 부족한 기본 도구 지급. 기본 철 곡괭이·낚싯대 |
| `/stock shop` | 모두 | 판매 단가·재료별 전량 판매 GUI |
| `/stock sell` | 모두 | 가방의 판매 가능한 일반 아이템 전량 판매 |
| `/stock start` | OP / `stock.admin` | 개장 또는 이어서 진행 |
| `/stock stop` | 관리자 | 휴장·즉시 저장. 자산 초기화 없음 |
| `/stock news` | 관리자 | 무작위 뉴스 즉시 발표 |
| `/stock setprice 유리등극장 200000` | 관리자 | 전체 회사명으로 가격 설정 |
| `/stock lender spawn` / `/stock lender spawn MHF_Villager` | 관리자 | 현재 위치에 사채업자 1명 소환·이동. 닉네임으로 스킨 선택 가능 |
| `/stock lender skin MHF_Villager` | 관리자 | 사채업자 스킨 변경·저장 |
| `/stock lender list` | 관리자 | 저장된 사채업자 위치·스킨 확인 |
| `/stock lender remove` | 관리자 | 사채업자 제거·자동 복원 중지 |
| `/stock loanadmin` / `/stock lender settings` | 관리자 | 사채 규칙을 게임 안에서 수정 |
| `/stock reload` | 관리자 | 주식·맵·수입·사채업자 설정 검증 후 적용 |
| `/stock interval 600` | 관리자 | 원하는 초 단위 간격 지정·저장. 0·소수도 가능 |
| `/stock initialcash 1000000` | 관리자 | 신규 참가·다음 초기화에 지급할 금액 저장. 기존 자산 유지 |
| `/stock reset` | 관리자 | 전체 초기화 범위와 확인 명령 안내 |
| `/stock reset confirm` | 관리자 | 전체 자산·주가·뉴스 초기화. 직전 백업 후 휴장 |
| `/stock status` | 관리자 | 시장·저장 계정·맵·종목 상태 확인 |

관리자 예시: `/stock setprice 새벽제분 100000`, `/stock skin 새벽제분 MHF_Melon`. `/stock menu`는 거래 메뉴, `/stock parkour leave`는 점프맵 퇴장입니다.
