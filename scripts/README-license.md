# 離線 Pro 發卡

在專案外的安全目錄中，由授權管理者建立 P-256 金鑰。將私鑰離線備份。下列命令的公鑰輸出不含私鑰。

```sh
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -aes-256-cbc -out roadguard-pro.pem
openssl pkey -in roadguard-pro.pem -pubout -outform DER | openssl base64 -A
```

將第二個命令的輸出設為建置環境變數 `ROADGUARD_PRO_PUBLIC_KEY`。換簽重裝後，以客戶新設備碼核對原權益再補發。效期以台北日期計算，發卡日為第一天。

```sh
python scripts/issue_license.py --key /secure/roadguard-pro.pem --device RG-1234-5678-9ABC-DEF0 --days 365
python scripts/issue_license.py --key /secure/roadguard-pro.pem --device RG-1234-5678-9ABC-DEF0 --lifetime
```
