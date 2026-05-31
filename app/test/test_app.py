import sys
import os

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '../src')))

from app import app

def test_hello_world():
    client = app.test_client()
    
    response = client.get('/')
    
    assert response.status_code == 200
    
    data = response.get_json()
    assert data["message"] == "Hello my py app in k8s"

def test_health():
    client = app.test_client()
    response = client.get('/health')
    assert response.status_code == 200
    assert response.get_json()["status"] == "healthy"
