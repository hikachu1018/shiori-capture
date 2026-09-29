import sys
import traceback

if len(sys.argv) == 4 and sys.argv[1] == "--verify-ocr":
    from pathlib import Path
    from PIL import Image
    from shiori.capture import JapaneseOcr

    source, destination = map(Path, sys.argv[2:])
    try:
        result = JapaneseOcr().read(Image.open(source), True)
        destination.write_text(result, encoding="utf-8")
    except Exception as exc:
        destination.write_text(traceback.format_exc(), encoding="utf-8")
        raise
else:
    from shiori.app import App, acquire_single_instance

    if acquire_single_instance():
        App().run()
