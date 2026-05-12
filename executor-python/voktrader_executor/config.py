from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    executor_api_token: str = Field(default="change-me", alias="EXECUTOR_API_TOKEN")
    executor_dry_run: bool = Field(default=True, alias="EXECUTOR_DRY_RUN")

    polymarket_host: str = Field(default="https://clob.polymarket.com", alias="POLYMARKET_HOST")
    polymarket_chain_id: int = Field(default=137, alias="POLYMARKET_CHAIN_ID")
    polymarket_funder: str | None = Field(default=None, alias="POLYMARKET_FUNDER")
    polymarket_private_key: str | None = Field(default=None, alias="POLYMARKET_PRIVATE_KEY")
    polymarket_signature_type: int = Field(default=0, alias="POLYMARKET_SIGNATURE_TYPE")

    polymarket_api_key: str | None = Field(default=None, alias="POLYMARKET_API_KEY")
    polymarket_api_secret: str | None = Field(default=None, alias="POLYMARKET_API_SECRET")
    polymarket_api_passphrase: str | None = Field(default=None, alias="POLYMARKET_API_PASSPHRASE")

    max_order_amount_usd: float = Field(default=5.0, alias="MAX_ORDER_AMOUNT_USD")
    require_fok: bool = Field(default=False, alias="REQUIRE_FOK")
    gtd_expiration_seconds: int = Field(default=90, alias="GTD_EXPIRATION_SECONDS")
