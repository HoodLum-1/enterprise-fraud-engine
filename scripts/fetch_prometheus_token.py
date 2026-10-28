import urllib.request, urllib.parse, json
data = urllib.parse.urlencode({
    'client_id': 'fraud-engine-client',
    'username': '<ENTER_USERNAME>',
    'password': '<ENTER_PASSWORD>',
    'grant_type': 'password'
}).encode()
req = urllib.request.Request('http://localhost:8180/realms/fraud-engine/protocol/openid-connect/token', data=data)
try:
    with urllib.request.urlopen(req) as response:
        token = json.loads(response.read().decode()).get('access_token', '')
        with open('./ops/prometheus/fraud-engine.token', 'w') as f:
            f.write(token)
        print('✅ Fresh token successfully written to fraud-engine.token')
except Exception as e:
    print('❌ Error fetching token:', e)
