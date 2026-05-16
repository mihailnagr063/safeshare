//! `ssf1-tool` — CLI reference implementation and test harness for
//! the SSF1 format.
//!
//! Subcommands:
//!   encrypt <plaintext_path> <output_path>
//!       Emits the SSF1 blob and prints the transfer code to stdout.
//!   encrypt-det <plaintext_path> <output_path> <key_hex> <r_hex> <file_id_hex>
//!       Deterministic encryption for reproducible test vectors.
//!   decrypt <input_path> <output_path> <code>
//!       Parses the transfer code, decrypts, writes plaintext.
//!   vectors
//!       Emits a block of test vectors suitable for pasting into
//!       PROTOCOL.md §9.

use std::fs::File;
use std::io::{BufReader, BufWriter, Write};
use std::path::PathBuf;

use anyhow::{anyhow, bail, Context, Result};
use sha2::{Digest, Sha256};
use ssf1::{
    code::TransferCode,
    format::{decrypt_stream, encrypt_stream, KeyMaterial},
};

fn main() -> Result<()> {
    let mut args = std::env::args().skip(1);
    let cmd = args.next().ok_or_else(|| anyhow!("missing subcommand"))?;
    match cmd.as_str() {
        "encrypt" => cmd_encrypt(args.collect()),
        "encrypt-det" => cmd_encrypt_det(args.collect()),
        "decrypt" => cmd_decrypt(args.collect()),
        "decrypt-raw" => cmd_decrypt_raw(args.collect()),
        "vectors" => cmd_vectors(),
        "parse-code" => cmd_parse_code(args.collect()),
        "--help" | "-h" | "help" => {
            print_usage();
            Ok(())
        }
        other => {
            eprintln!("unknown subcommand: {other}");
            print_usage();
            std::process::exit(2);
        }
    }
}

fn print_usage() {
    eprintln!(
        "usage:
  ssf1-tool encrypt <plaintext_path> <output_path>
  ssf1-tool encrypt-det <plaintext_path> <output_path> <key_hex> <r_hex> <file_id_hex>
  ssf1-tool decrypt <ciphertext_path> <output_path> <transfer_code>
  ssf1-tool decrypt-raw <ciphertext_path> <output_path> <key_hex> <r_hex>
  ssf1-tool vectors
  ssf1-tool parse-code <transfer_code>"
    );
}

fn cmd_encrypt(args: Vec<String>) -> Result<()> {
    if args.len() != 2 {
        bail!("encrypt: expected 2 arguments");
    }
    let plain_path = PathBuf::from(&args[0]);
    let out_path = PathBuf::from(&args[1]);

    let filename = plain_path
        .file_name()
        .ok_or_else(|| anyhow!("invalid plaintext path"))?
        .to_string_lossy()
        .into_owned();
    let size = std::fs::metadata(&plain_path)?.len();
    if size == 0 {
        bail!("plaintext is empty");
    }

    let km = KeyMaterial::generate();
    let mut file_id = [0u8; 5];
    rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut file_id);

    let reader = BufReader::new(File::open(&plain_path)?);
    let mut writer = BufWriter::new(File::create(&out_path)?);
    encrypt_stream(&km, &filename, size, reader, &mut writer).map_err(|e| anyhow!("{e}"))?;
    writer.flush()?;

    let code = TransferCode {
        file_id,
        km: km.clone(),
    }
    .format()
    .map_err(|e| anyhow!("{e}"))?;
    println!("{}", code);
    eprintln!(
        "wrote {} bytes of ciphertext to {}",
        std::fs::metadata(&out_path)?.len(),
        out_path.display()
    );
    Ok(())
}

fn cmd_encrypt_det(args: Vec<String>) -> Result<()> {
    if args.len() != 5 {
        bail!("encrypt-det: expected 5 arguments");
    }
    let plain_path = PathBuf::from(&args[0]);
    let out_path = PathBuf::from(&args[1]);
    let key = parse_hex_fixed::<16>(&args[2]).context("key_hex")?;
    let r = parse_hex_fixed::<4>(&args[3]).context("r_hex")?;
    let file_id = parse_hex_fixed::<5>(&args[4]).context("file_id_hex")?;

    let filename = plain_path
        .file_name()
        .ok_or_else(|| anyhow!("invalid plaintext path"))?
        .to_string_lossy()
        .into_owned();
    let size = std::fs::metadata(&plain_path)?.len();
    if size == 0 {
        bail!("plaintext is empty");
    }

    let km = KeyMaterial { key, r };
    let reader = BufReader::new(File::open(&plain_path)?);
    let mut writer = BufWriter::new(File::create(&out_path)?);
    encrypt_stream(&km, &filename, size, reader, &mut writer).map_err(|e| anyhow!("{e}"))?;
    writer.flush()?;
    let code = TransferCode { file_id, km }
        .format()
        .map_err(|e| anyhow!("{e}"))?;
    println!("{}", code);
    Ok(())
}

fn cmd_decrypt(args: Vec<String>) -> Result<()> {
    if args.len() != 3 {
        bail!("decrypt: expected 3 arguments");
    }
    let in_path = PathBuf::from(&args[0]);
    let out_path = PathBuf::from(&args[1]);
    let code_str = &args[2];

    let code = TransferCode::parse(code_str).map_err(|e| anyhow!("{e}"))?;
    let reader = BufReader::new(File::open(&in_path)?);
    let mut writer = BufWriter::new(File::create(&out_path)?);
    let filename = decrypt_stream(&code.km.key, Some(&code.km.r), reader, &mut writer)
        .map_err(|e| anyhow!("{e}"))?;
    writer.flush()?;
    eprintln!("decrypted filename: {filename}");
    Ok(())
}

fn cmd_decrypt_raw(args: Vec<String>) -> Result<()> {
    if args.len() != 4 {
        bail!("decrypt-raw: expected 4 arguments");
    }
    let in_path = PathBuf::from(&args[0]);
    let out_path = PathBuf::from(&args[1]);
    let key = parse_hex_fixed::<16>(&args[2])?;
    let r = parse_hex_fixed::<4>(&args[3])?;
    let reader = BufReader::new(File::open(&in_path)?);
    let mut writer = BufWriter::new(File::create(&out_path)?);
    let filename =
        decrypt_stream(&key, Some(&r), reader, &mut writer).map_err(|e| anyhow!("{e}"))?;
    writer.flush()?;
    eprintln!("decrypted filename: {filename}");
    Ok(())
}

fn cmd_parse_code(args: Vec<String>) -> Result<()> {
    if args.len() != 1 {
        bail!("parse-code: expected 1 argument");
    }
    let code = TransferCode::parse(&args[0]).map_err(|e| anyhow!("{e}"))?;
    println!("file_id_hex = {}", hex::encode(code.file_id));
    println!("key_hex     = {}", hex::encode(code.km.key));
    println!("r_hex       = {}", hex::encode(code.km.r));
    let mut payload = [0u8; 20];
    payload[..16].copy_from_slice(&code.km.key);
    payload[16..].copy_from_slice(&code.km.r);
    let digest = Sha256::digest(payload);
    println!("checksum16  = {}", hex::encode(&digest[..2]));
    Ok(())
}

fn cmd_vectors() -> Result<()> {
    // Deterministic inputs.
    let key = hex_to_array::<16>("000102030405060708090a0b0c0d0e0f");
    let r = hex_to_array::<4>("10111213");
    let file_id = hex_to_array::<5>("1415161718");
    let km = KeyMaterial { key, r };

    // Vector 1: transfer code from (K, R, file_id).
    let code = TransferCode {
        file_id,
        km: km.clone(),
    };
    println!("## Transfer code vector");
    println!("key         = {}", hex::encode(km.key));
    println!("r           = {}", hex::encode(km.r));
    println!("file_id     = {}", hex::encode(file_id));
    let mut payload = [0u8; 20];
    payload[..16].copy_from_slice(&km.key);
    payload[16..].copy_from_slice(&km.r);
    let digest = Sha256::digest(payload);
    println!("checksum16  = {}", hex::encode(&digest[..2]));
    match code.format() {
        Ok(s) => println!("code        = {}", s),
        Err(e) => println!("code        = <error: {e}; wordlist not installed>"),
    }

    // Vector 2: single-chunk encryption of "Hello, SafeShare!\n".
    println!();
    println!("## Single-chunk encryption vector");
    let plain = b"Hello, SafeShare!\n";
    let filename = "greet.txt";
    let mut ct = Vec::new();
    encrypt_stream(&km, filename, plain.len() as u64, plain.as_slice(), &mut ct)
        .map_err(|e| anyhow!("{e}"))?;
    println!("filename    = {:?}", filename);
    println!("plaintext   = {:?}", std::str::from_utf8(plain).unwrap());
    println!("ciphertext ({} bytes) = {}", ct.len(), hex::encode(&ct));

    // Dump the header for clarity.
    println!("  header      = {}", hex::encode(&ct[..24]));
    let name_len = u16::from_be_bytes(ct[24..26].try_into().unwrap()) as usize;
    println!("  name_len    = {}", name_len);
    println!("  name_blob   = {}", hex::encode(&ct[26..26 + name_len]));
    println!(
        "  chunk0_ct   = {}",
        hex::encode(&ct[26 + name_len..26 + name_len + plain.len()])
    );
    println!(
        "  chunk0_tag  = {}",
        hex::encode(&ct[26 + name_len + plain.len()..])
    );

    // Self-test: decrypt must reproduce the plaintext.
    let mut back = Vec::new();
    let got_name = decrypt_stream(&km.key, Some(&km.r), ct.as_slice(), &mut back)
        .map_err(|e| anyhow!("{e}"))?;
    assert_eq!(got_name, filename);
    assert_eq!(back, plain);
    println!("self-test   = ok");
    Ok(())
}

fn parse_hex_fixed<const N: usize>(s: &str) -> Result<[u8; N]> {
    let bytes = hex::decode(s).with_context(|| format!("hex decode {s}"))?;
    if bytes.len() != N {
        bail!("expected {} bytes, got {}", N, bytes.len());
    }
    let mut out = [0u8; N];
    out.copy_from_slice(&bytes);
    Ok(out)
}

fn hex_to_array<const N: usize>(s: &str) -> [u8; N] {
    let bytes = hex::decode(s).expect("hex");
    assert_eq!(bytes.len(), N);
    let mut out = [0u8; N];
    out.copy_from_slice(&bytes);
    out
}
