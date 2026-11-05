# Переходник для кнопки в Telegram

Кнопки ботов Telegram принимают только `http(s)://`-ссылки, поэтому кнопка ведёт на эту страницу,
а она открывает приложение по `ethicvpn://add`: приложение предлагает добавить подписку, которую человек скопировал в боте.
С `?url=<подписка>` страница передаёт приложению эту подписку.

## Установка на сервер (nginx в Docker, порт 8080)

```bash
mkdir -p /root/ethicvpn-link
# положите сюда index.html из этой папки
docker run -d --name ethicvpn-link --restart unless-stopped \
  -p 8080:80 -v /root/ethicvpn-link:/usr/share/nginx/html:ro nginx:alpine
```

## Ссылка для кнопки

```
http://<IP сервера>:8080/
```

Пример на Python (aiogram / python-telegram-bot):

```python
from urllib.parse import quote
url = f"http://176.124.220.95:8080/?url={quote(subscription_url, safe='')}"
```
