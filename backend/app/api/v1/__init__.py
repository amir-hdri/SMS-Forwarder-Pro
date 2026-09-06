from fastapi import APIRouter
from app.api.v1.endpoints.rpa import router as rpa_router

api_v1_router = APIRouter(prefix="/api/v1")
api_v1_router.include_router(rpa_router, prefix="/rpa", tags=["rpa"])

__all__ = ["api_v1_router"]
