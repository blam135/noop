"""Seed synthetic manual nutrition values in a disposable CI simulator only."""
import json
import sys
import time
from pathlib import Path

folder = Path(sys.argv[1]) / 'Library/Application Support/Nutrition'
folder.mkdir(parents=True, exist_ok=True)
photo = 'preview-fruit.jpg'
# Use the same attributed OpenCV sample as the Android captures.
import urllib.request
urllib.request.urlretrieve('https://raw.githubusercontent.com/opencv/opencv/4.x/samples/data/fruits.jpg', folder / photo)
meal = dict(id='preview-fruit', timestamp=time.time()*1000, name='Fruit bowl',
            portion='One bowl', calories=180, protein=2, carbs=42, fat=1,
            notes='Manually entered demo values; no live AI analysis.', photo=photo,
            provider='', model='')
(folder / 'meals-v1.json').write_text(json.dumps([meal]))
