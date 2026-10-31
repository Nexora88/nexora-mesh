use btleplug::api::{Central, Manager as _, Peripheral as _, ScanFilter};
use btleplug::platform::{Adapter, Manager};
use serde::Serialize;
use std::sync::Mutex;
use tauri::State;

struct RadioState(Mutex<Option<Adapter>>);

#[derive(Serialize)]
struct NearbyDevice {
    id: String,
    name: String,
    rssi: Option<i16>,
}

#[tauri::command]
async fn start_ble_discovery(state: State<'_, RadioState>) -> Result<String, String> {
    let manager = Manager::new().await.map_err(|e| e.to_string())?;
    let adapters = manager.adapters().await.map_err(|e| e.to_string())?;
    let adapter = adapters.into_iter().next().ok_or("No BLE adapter available")?;
    adapter.start_scan(ScanFilter::default()).await.map_err(|e| e.to_string())?;
    let label = adapter.adapter_info().await.unwrap_or_else(|_| "BLE adapter".into());
    *state.0.lock().map_err(|e| e.to_string())? = Some(adapter);
    Ok(format!("Discovery started on {label}"))
}

#[tauri::command]
async fn stop_ble_discovery(state: State<'_, RadioState>) -> Result<(), String> {
    let adapter = state.0.lock().map_err(|e| e.to_string())?.clone();
    if let Some(adapter) = adapter {
        adapter.stop_scan().await.map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
async fn nearby_ble_devices(state: State<'_, RadioState>) -> Result<Vec<NearbyDevice>, String> {
    let adapter = state.0.lock().map_err(|e| e.to_string())?.clone()
        .ok_or("Start discovery first")?;
    let peripherals = adapter.peripherals().await.map_err(|e| e.to_string())?;
    let mut devices = Vec::new();
    for peripheral in peripherals {
        let properties = peripheral.properties().await.map_err(|e| e.to_string())?;
        if let Some(properties) = properties {
            devices.push(NearbyDevice {
                id: peripheral.id().to_string(),
                name: properties.local_name.unwrap_or_else(|| "Nearby BLE device".into()),
                rssi: properties.rssi,
            });
        }
    }
    Ok(devices)
}

pub fn run() {
    tauri::Builder::default()
        .manage(RadioState(Mutex::new(None)))
        .invoke_handler(tauri::generate_handler![
            start_ble_discovery,
            stop_ble_discovery,
            nearby_ble_devices
        ])
        .run(tauri::generate_context!())
        .expect("error while running Nexora Mesh desktop");
}
