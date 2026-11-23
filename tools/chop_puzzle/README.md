# Shared Python environment

This folder holds the virtualenv used by reveal prep and the stage-reveal tool. The grid chop script is gone.

```powershell
python -m venv tools\chop_puzzle\.venv
tools\chop_puzzle\.venv\Scripts\pip install -r tools\prep_reveal\requirements.txt
```
